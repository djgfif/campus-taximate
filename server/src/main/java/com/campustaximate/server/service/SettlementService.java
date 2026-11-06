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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final long MAX_AMOUNT = 10_000_000;
    private static final Set<String> RECEIPT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;
    private final AppClock clock;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Transactional
    public void submit(long memberId, long partyId, long amount, MultipartFile receipt) {
        Party party = lockAsPayer(memberId, partyId);
        List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
        if (party.getStatus() != PartyStatus.CONFIRMED
                || riders.stream().anyMatch(r -> r.getStatus() != RequestStatus.COMPLETED)) {
            throw ApiException.conflict("NOT_DEPARTED", "출발 처리가 끝난 뒤에 입력할 수 있습니다.");
        }
        List<Long> shares = shares(amount, riders);
        Settlement settlement = settlements.save(new Settlement(party, amount, store(receipt),
                receipt.getContentType(), clock.now()));
        for (int i = 0; i < riders.size() - 1; i++) {
            transfers.save(new TransferRequest(settlement, riders.get(i).getMember(), shares.get(i)));
        }
        party.setStatus(PartyStatus.SETTLING);
    }

    @Transactional
    public void correct(long memberId, long partyId, long amount) {
        Party party = lockAsPayer(memberId, partyId);
        Settlement settlement = settlements.findByParty(party)
                .orElseThrow(() -> ApiException.notFound("정산이 없습니다."));
        List<TransferRequest> current = transfers.findBySettlementOrderById(settlement);
        if (current.stream().anyMatch(t -> t.getStatus() != TransferStatus.REQUESTED)) {
            throw ApiException.conflict("ALREADY_SENT", "누군가 이미 보내 금액을 고칠 수 없습니다.");
        }
        List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
        List<Long> shares = shares(amount, riders);
        for (TransferRequest transfer : current) {
            for (int i = 0; i < riders.size() - 1; i++) {
                if (riders.get(i).getMember().getId().equals(transfer.getSender().getId())) {
                    transfer.setAmount(shares.get(i));
                }
            }
        }
        settlement.setPaidAmount(amount);
        settlement.setRevision(settlement.getRevision() + 1);
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
            boolean allReceived = transfers.findBySettlementOrderById(transfer.getSettlement()).stream()
                    .allMatch(t -> t.getStatus() == TransferStatus.RECEIVED);
            if (allReceived) {
                party.setStatus(PartyStatus.COMPLETED);
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

    private List<Long> shares(long amount, List<RideRequest> riders) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw ApiException.badRequest("INVALID_AMOUNT", "금액을 다시 확인해 주세요.");
        }
        List<Long> units = new ArrayList<>();
        riders.forEach(r -> units.add(r.getRatioUnits()));
        try {
            return SettlementCalculator.shares(amount, units);
        } catch (SettlementCalculator.NegativeShareException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "금액이 너무 작습니다. 다시 확인해 주세요.");
        }
    }

    private String store(MultipartFile receipt) {
        if (receipt == null || receipt.isEmpty()) {
            throw ApiException.badRequest("RECEIPT_REQUIRED", "결제 내역 사진을 올려 주세요.");
        }
        if (receipt.getContentType() == null || !RECEIPT_TYPES.contains(receipt.getContentType())) {
            throw ApiException.badRequest("RECEIPT_TYPE", "JPG, PNG, WEBP 사진만 올릴 수 있습니다.");
        }
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
