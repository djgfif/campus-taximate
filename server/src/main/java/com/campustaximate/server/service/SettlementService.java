package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.domain.TransferStatus;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private static final long MAX_AMOUNT = 10_000_000;
    private static final Duration RECEIPT_KEEP = Duration.ofDays(7);
    private static final Set<String> RECEIPT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;
    private final AppClock clock;
    private final PlatformTransactionManager transactionManager;
    private final ChangeBus changes;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public record ShareView(long memberId, String nickname, long amount, boolean payer) {
    }

    @Transactional
    public void submit(long memberId, long partyId, long amount, MultipartFile receipt,
                       String bankName, String accountNumber) {
        Party party = lockAsPayer(memberId, partyId);
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        requireDeparted(party, riders);
        requireAccount(bankName, accountNumber);
        List<Long> shares = shares(amount, riders, party);
        create(party, riders, shares, amount, store(receipt), receipt.getContentType(), bankName, accountNumber);
    }

    /** 시연의 샘플 결제자 전용. 사진 없이 정산을 만든다. */
    @Transactional
    public void submitForDemo(long memberId, long partyId, long amount, String bankName, String accountNumber) {
        Party party = lockAsPayer(memberId, partyId);
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        requireDeparted(party, riders);
        create(party, riders, shares(amount, riders, party), amount, null, null, bankName, accountNumber);
    }

    private void create(Party party, List<RideRequest> riders, List<Long> shares, long amount, String receiptPath,
                        String contentType, String bankName, String accountNumber) {
        Settlement settlement = settlements.save(new Settlement(party, amount, receiptPath, contentType,
                bankName.trim(), accountNumber.trim(), clock.now()));
        for (int i = 0; i < riders.size(); i++) {
            if (!riders.get(i).getMember().getId().equals(party.getPayer().getId())) {
                transfers.save(new TransferRequest(settlement, riders.get(i).getMember(), shares.get(i)));
            }
        }
        party.setStatus(PartyStatus.SETTLING);
    }

    private void requireDeparted(Party party, List<RideRequest> riders) {
        if (party.getStatus() != PartyStatus.CONFIRMED
                || riders.stream().anyMatch(r -> r.getStatus() != RequestStatus.COMPLETED)) {
            throw ApiException.conflict("NOT_DEPARTED", "출발 처리가 끝난 뒤에 입력할 수 있습니다.");
        }
    }

    private static void requireAccount(String bankName, String accountNumber) {
        if (bankName == null || bankName.isBlank() || bankName.length() > 30
                || accountNumber == null || !accountNumber.trim().matches("[0-9][0-9-]{4,38}[0-9]")) {
            throw ApiException.badRequest("ACCOUNT_REQUIRED", "은행과 계좌번호를 확인해 주세요.");
        }
    }

    /** 금액을 넣기 전에 결제자가 사람별 금액을 미리 본다. */
    @Transactional(readOnly = true)
    public List<ShareView> preview(long memberId, long partyId, long amount) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        if (!party.getPayer().getId().equals(memberId)) {
            throw ApiException.forbidden("결제자만 볼 수 있습니다.");
        }
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        List<Long> shares = shares(amount, riders, party);
        List<ShareView> views = new ArrayList<>();
        for (int i = 0; i < riders.size(); i++) {
            Member member = riders.get(i).getMember();
            views.add(new ShareView(member.getId(), member.getNickname(), shares.get(i),
                    member.getId().equals(memberId)));
        }
        return views;
    }

    @Transactional
    public void correct(long memberId, long partyId, long amount, MultipartFile receipt,
                        String bankName, String accountNumber) {
        Party party = lockAsPayer(memberId, partyId);
        Settlement settlement = settlements.findByParty(party)
                .orElseThrow(() -> ApiException.notFound("정산이 없습니다."));
        List<TransferRequest> current = transfers.findBySettlementOrderById(settlement);
        if (current.stream().anyMatch(t -> t.getStatus() != TransferStatus.REQUESTED)) {
            throw ApiException.conflict("ALREADY_SENT", "누군가 이미 보내 금액을 고칠 수 없습니다.");
        }
        requireAccount(bankName, accountNumber);
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        List<Long> shares = shares(amount, riders, party);
        for (TransferRequest transfer : current) {
            for (int i = 0; i < riders.size(); i++) {
                if (riders.get(i).getMember().getId().equals(transfer.getSender().getId())) {
                    transfer.setAmount(shares.get(i));
                }
            }
        }
        String oldPath = settlement.getReceiptPath();
        settlement.setReceiptPath(store(receipt));
        settlement.setReceiptContentType(receipt.getContentType());
        settlement.setBankName(bankName.trim());
        settlement.setAccountNumber(accountNumber.trim());
        settlement.setPaidAmount(amount);
        settlement.setRevision(settlement.getRevision() + 1);
        settlement.setStoredAt(clock.now());
        deleteFile(oldPath);
    }

    /** 보관 기간이 지난 사진과 계좌를 지운다. 금액과 정산 기록은 남긴다. */
    @Transactional
    public void purgeReceipts() {
        Instant limit = clock.now().minus(RECEIPT_KEEP);
        for (Settlement settlement : settlements.findExpired(limit)) {
            deleteFile(settlement.getReceiptPath());
            settlement.setReceiptPath(null);
            settlement.clearAccount();
        }
    }

    private void deleteFile(String path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 일행 잠금을 트랜잭션의 첫 읽기로 잡아야 최신 값을 본다. 그래서 일행 번호는 트랜잭션 밖에서 찾는다. */
    public void markSent(long memberId, long transferId, int revision) {
        long partyId = partyIdOf(transferId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            parties.findByIdForUpdate(partyId).orElseThrow();
            TransferRequest transfer = transfers.findById(transferId).orElseThrow();
            if (!transfer.getSender().getId().equals(memberId)) {
                throw ApiException.forbidden("본인 송금만 표시할 수 있습니다.");
            }
            if (transfer.getStatus() != TransferStatus.REQUESTED) {
                throw ApiException.conflict("BAD_STATE", "이미 처리된 송금입니다.");
            }
            if (transfer.getSettlement().getRevision() != revision) {
                throw ApiException.conflict("AMOUNT_CHANGED", "금액이 바뀌었습니다. 화면을 새로고침해 주세요.");
            }
            transfer.setStatus(TransferStatus.SENT);
            log.info("정산 보냄. party={} transfer={} member={}", partyId, transferId, memberId);
            changes.partyChanged(partyId);
        });
    }

    public void markReceived(long memberId, long transferId) {
        long partyId = partyIdOf(transferId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Party party = parties.findByIdForUpdate(partyId).orElseThrow();
            TransferRequest transfer = transfers.findById(transferId).orElseThrow();
            if (!party.getPayer().getId().equals(memberId)) {
                throw ApiException.forbidden("결제자만 표시할 수 있습니다.");
            }
            if (transfer.getStatus() != TransferStatus.SENT) {
                throw ApiException.conflict("BAD_STATE", "보냄 표시가 된 송금만 받음으로 바꿀 수 있습니다.");
            }
            transfer.setStatus(TransferStatus.RECEIVED);
            log.info("정산 받음. party={} transfer={} member={}", partyId, transferId, memberId);
            changes.partyChanged(partyId);
            boolean allReceived = transfers.findBySettlementOrderById(transfer.getSettlement()).stream()
                    .allMatch(t -> t.getStatus() == TransferStatus.RECEIVED);
            if (allReceived) {
                party.setStatus(PartyStatus.COMPLETED);
                transfer.getSettlement().clearAccount();
            }
        });
    }

    private long partyIdOf(long transferId) {
        return transfers.findPartyId(transferId).orElseThrow(() -> ApiException.notFound("없는 송금 요청입니다."));
    }

    private Party lockAsPayer(long memberId, long partyId) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        Member payer = party.getPayer();
        if (!payer.getId().equals(memberId)) {
            throw ApiException.forbidden("결제자만 입력할 수 있습니다.");
        }
        return party;
    }

    private List<Long> shares(long amount, List<RideRequest> riders, Party party) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw ApiException.badRequest("INVALID_AMOUNT", "금액을 다시 확인해 주세요.");
        }
        List<Long> units = new ArrayList<>();
        int payerIndex = -1;
        for (int i = 0; i < riders.size(); i++) {
            units.add(riders.get(i).getRatioUnits());
            if (riders.get(i).getMember().getId().equals(party.getPayer().getId())) {
                payerIndex = i;
            }
        }
        try {
            return SettlementCalculator.shares(amount, units, payerIndex);
        } catch (SettlementCalculator.NegativeShareException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "금액이 너무 작습니다. 다시 확인해 주세요.");
        }
    }

    /** 브라우저가 보낸 형식은 믿지 않고 파일 앞부분으로 확인한다. */
    private static void requireImageBytes(MultipartFile receipt) {
        byte[] head = new byte[12];
        try (InputStream in = receipt.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            boolean jpeg = read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            boolean png = read >= 8 && Arrays.equals(Arrays.copyOf(head, 8), PNG);
            boolean webp = read >= 12 && new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP");
            if (!jpeg && !png && !webp) {
                throw ApiException.badRequest("RECEIPT_TYPE", "JPG, PNG, WEBP 사진만 올릴 수 있습니다.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String store(MultipartFile receipt) {
        if (receipt == null || receipt.isEmpty()) {
            throw ApiException.badRequest("RECEIPT_REQUIRED", "결제 내역 사진을 올려 주세요.");
        }
        if (receipt.getContentType() == null || !RECEIPT_TYPES.contains(receipt.getContentType())) {
            throw ApiException.badRequest("RECEIPT_TYPE", "JPG, PNG, WEBP 사진만 올릴 수 있습니다.");
        }
        requireImageBytes(receipt);
        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString());
            receipt.transferTo(target.toAbsolutePath());
            return target.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
