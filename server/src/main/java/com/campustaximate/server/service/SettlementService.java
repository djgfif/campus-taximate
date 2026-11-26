package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.PaymentOrder;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.domain.TransferStatus;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.PaymentOrderRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementConfirmationRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    /** 실제 요금을 올린 뒤 요금 이의를 받는 기간. */
    private static final Duration OBJECTION_WINDOW = Duration.ofHours(24);
    private static final Duration POSTPAID_OBJECTION_WINDOW = Duration.ofHours(6);
    private static final Set<String> RECEIPT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;
    private final AppClock clock;
    private final PlatformTransactionManager transactionManager;
    private final ChangeBus changes;
    private final RideConfirmationService rideConfirmations;
    private final com.campustaximate.server.repository.SettlementShareRepository allocations;
    private final com.campustaximate.server.repository.FareObjectionRepository objections;
    private final PaymentOrderRepository orders;
    private final SettlementConfirmationRepository confirmations;
    private final PostpaidOrderSyncService postpaidOrders;
    private final PartyChatService chatLog;
    private final NoShowService noShows;

    private final MediaFiles media;

    public record ShareView(long memberId, String nickname, long amount, boolean payer) {
    }

    @Transactional
    public void submit(long memberId, long partyId, long amount, MultipartFile receipt,
                       String bankName, String accountNumber) {
        Party party = lockAsPayer(memberId, partyId);
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        requireDeparted(party, riders);
        riders = requests.findByPartyOrderByRideOrder(party);
        if (!party.paymentFlow()) requireAccount(bankName, accountNumber);
        List<Long> shares = shares(amount, riders, party);
        StoredReceipt stored = store(receipt);
        create(party, riders, shares, amount, stored.path(), stored.contentType(), bankName, accountNumber);
    }

    /** 시연의 샘플 결제자 전용. 사진 없이 정산을 만든다. */
    @Transactional
    public void submitForDemo(long memberId, long partyId, long amount, String bankName, String accountNumber) {
        Party party = lockAsPayer(memberId, partyId);
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        requireDeparted(party, riders);
        riders = requests.findByPartyOrderByRideOrder(party);
        create(party, riders, shares(amount, riders, party), amount, null, null, bankName, accountNumber);
    }

    private Duration objectionWindow(Party party) {
        return party.postpaidFlow() ? POSTPAID_OBJECTION_WINDOW : OBJECTION_WINDOW;
    }

    private void create(Party party, List<RideRequest> riders, List<Long> shares, long amount, String receiptPath,
                        String contentType, String bankName, String accountNumber) {
        if (party.paymentFlow()) {
            // 저장 시각과 이의 마감은 같은 순간에서 잰다. 시계를 두 번 읽으면 마감이 저장+이의기간보다 미세하게 늦어진다.
            Instant storedAt = clock.now();
            Settlement settlement = settlements.save(new Settlement(party, amount, receiptPath, contentType, null, null, storedAt));
            settlement.setObjectionDeadlineAt(storedAt.plus(objectionWindow(party)));
            saveAllocations(party, settlement, riders, shares);
            if(party.getFareRecoveryAt()==null && rideConfirmations.hasNegativeAnswer(party.getId())) { party.setStatus(PartyStatus.REVIEW_PENDING); party.setAdminReviewRequired(true); party.setReviewReason("RIDE_DISPUTE"); }
            else if(exceptionalFare(party, amount, payerShareOf(party, riders, shares))) { party.setStatus(PartyStatus.REVIEW_PENDING); party.setAdminReviewRequired(true); party.setReviewReason("FARE_EXCEPTION"); }
            else party.setStatus(PartyStatus.OBJECTION_WINDOW);
            if (party.postpaidFlow()) {
                postpaidOrders.syncAfterSave(party, settlement, false);
                chatLog.system(party, String.format("결제자가 실제 요금 %,d원과 영수증을 올렸어요. 6시간 동안 확인·결제하거나 요금 이의를 낼 수 있어요.", amount));
            } else {
                chatLog.system(party, String.format("결제자가 실제 요금 %,d원과 영수증을 올렸어요. 24시간 동안 요금 이의를 낼 수 있어요.", amount));
            }
            return;
        }
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
        if (party.paymentFlow()) {
            Instant start = party.getFareRecoveryAt()!=null?party.getFareRecoveryAt():party.getStartedAt() == null ? party.getDepartureAt() : party.getStartedAt();
            if (!List.of(PartyStatus.AWAITING_FARE,PartyStatus.RIDE_UNCONFIRMED).contains(party.getStatus()) || !clock.now().isBefore(start.plusSeconds(24 * 3600))) {
                throw ApiException.conflict("NOT_AWAITING_FARE", "금액을 입력할 수 없는 상태이거나 입력 기한이 지났습니다.");
            }
            if(party.getStartedAt()==null) throw ApiException.conflict("NOT_STARTED","결제자가 운행 시작을 눌러야 해요.");
            noShows.closeBoarding(party);
            if(party.getBoardingDeadlineAt()!=null && clock.now().isBefore(party.getBoardingDeadlineAt()))
                throw ApiException.conflict("BOARDING_PENDING","탑승 번호 입력 기한이 끝나면 금액을 올릴 수 있어요. 정산 확정은 필요한 조사가 끝나야 해요.");
            return;
        }
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
        if (party.paymentFlow()) {
            if (party.getStatus() != PartyStatus.OBJECTION_WINDOW || !clock.now().isBefore(settlement.getObjectionDeadlineAt())
                    || objections.existsBySettlementId(settlement.getId())) {
                throw ApiException.conflict("CANNOT_CORRECT", "이의가 없고 이의 기간 안일 때만 금액을 고칠 수 있습니다.");
            }
            if (party.postpaidFlow() && (confirmations.existsBySettlementId(settlement.getId())
                    || orders.findByPartyIdOrderById(partyId).stream().anyMatch(o -> o.getPurpose() == PaymentPurpose.POSTPAID
                            && (o.getStatus() == PaymentStatus.PROCESSING
                                    || o.getApprovedAmount() > 0
                                    || (o.getStatus() == PaymentStatus.APPROVED && o.getAmount() > 0))))) {
                throw ApiException.conflict("ALREADY_PAID", "이미 확인·결제한 일행원이 있어 요금을 고칠 수 없어요.");
            }
            List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
            List<Long> values = shares(amount, riders, party);
            String previous = settlement.getReceiptPath();
            StoredReceipt stored = store(receipt);
            settlement.setReceiptPath(stored.path()); settlement.setReceiptContentType(stored.contentType());
            Instant storedAt = clock.now();
            settlement.setPaidAmount(amount); settlement.setRevision(settlement.getRevision() + 1); settlement.setStoredAt(storedAt);
            settlement.setObjectionDeadlineAt(storedAt.plus(objectionWindow(party))); settlement.clearAccount();
            if (party.postpaidFlow()) {
                confirmations.deleteBySettlementId(settlement.getId());
            }
            saveAllocations(party, settlement, riders, values);
            // 고친 금액도 확정 금액의 1.5배를 넘거나 절반 미만(또는 결제자 확정 몫 미만)이면 관리자 검토로 보낸다.
            if (exceptionalFare(party, amount, payerShareOf(party, riders, values))) { party.setStatus(PartyStatus.REVIEW_PENDING); party.setAdminReviewRequired(true); party.setReviewReason("FARE_EXCEPTION"); }
            if (party.postpaidFlow()) {
                postpaidOrders.syncAfterSave(party, settlement, false);
            }
            media.deleteAfterCommit(previous);
            return;
        }
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
        StoredReceipt stored = store(receipt);
        settlement.setReceiptPath(stored.path());
        settlement.setReceiptContentType(stored.contentType());
        settlement.setBankName(bankName.trim());
        settlement.setAccountNumber(accountNumber.trim());
        settlement.setPaidAmount(amount);
        settlement.setRevision(settlement.getRevision() + 1);
        settlement.setStoredAt(clock.now());
        media.deleteAfterCommit(oldPath);
    }

    /** 관리자 검토로 확정된 실제 요금. 이의 기간은 다시 열지 않는다. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void correctByAdmin(Party party,Settlement settlement,long amount) {
        var riders=requests.findByPartyOrderByRideOrder(party);
        var values=shares(amount,riders,party);
        settlement.setPaidAmount(amount);settlement.setRevision(settlement.getRevision()+1);
        saveAllocations(party,settlement,riders,values);
        if (party.postpaidFlow()) {
            postpaidOrders.syncAfterSave(party, settlement, true);
        }
    }

    private void saveAllocations(Party party, Settlement settlement, List<RideRequest> riders, List<Long> values) {
        List<com.campustaximate.server.domain.SettlementShare> old = allocations.findBySettlementIdOrderByRideOrder(settlement.getId());
        old.stream().filter(s->riders.stream().noneMatch(r->r.getMember().getId().equals(s.getMemberId()))).forEach(allocations::delete);
        for (int i = 0; i < riders.size(); i++) {
            RideRequest rider = riders.get(i);
            var share = old.stream().filter(a -> a.getMemberId().equals(rider.getMember().getId())).findFirst()
                    .orElseGet(com.campustaximate.server.domain.SettlementShare::new);
            share.setSettlementId(settlement.getId()); share.setPartyId(party.getId()); share.setMemberId(rider.getMember().getId());
            share.setRatioUnits(rider.getRatioUnits()); share.setRideOrder(rider.getRideOrder()); share.setAmount(values.get(i));
            share.setRawAmount(null); share.setCompensationCredit(0);
            share.setPayer(rider.getMember().getId().equals(party.getPayer().getId())); allocations.save(share);
        }
    }

    @Transactional
    public void objectToFare(long memberId, long partyId, String reason) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        Settlement settlement = settlements.findByParty(party).orElseThrow(() -> ApiException.notFound("정산이 없습니다."));
        if (requests.findByPartyOrderByRideOrder(party).stream().noneMatch(r -> r.getMember().getId() == memberId)) {
            throw ApiException.forbidden("일행원만 요금 이의를 낼 수 있습니다.");
        }
        if (!party.paymentFlow() || "RIDE_FINANCIAL_HOLD".equals(party.getReviewReason())
                || !List.of(PartyStatus.OBJECTION_WINDOW, PartyStatus.REVIEW_PENDING).contains(party.getStatus())
                || !clock.now().isBefore(settlement.getObjectionDeadlineAt())) throw ApiException.conflict("OBJECTION_CLOSED", "요금 이의 기간이 끝났습니다.");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw ApiException.badRequest("INVALID_OBJECTION", "이의 사유는 1~500자로 입력해 주세요.");
        if (party.postpaidFlow() && (confirmations.existsBySettlementIdAndMemberIdAndRevision(settlement.getId(), memberId, settlement.getRevision())
                || orders.findByPartyIdAndMemberIdAndPurpose(partyId, memberId, PaymentPurpose.POSTPAID)
                        .map(o -> o.getStatus() == PaymentStatus.PROCESSING
                                || o.getApprovedAmount() > 0
                                || (o.getStatus() == PaymentStatus.APPROVED && o.getAmount() > 0))
                        .orElse(false))) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "이미 정산을 확인하고 결제한 내역에는 요금 이의를 낼 수 없어요.");
        }
        if (objections.existsBySettlementIdAndMemberId(settlement.getId(), memberId)) return;
        var objection = new com.campustaximate.server.domain.FareObjection();
        objection.setSettlementId(settlement.getId()); objection.setMemberId(memberId); objection.setReason(reason.strip());
        objection.setCreatedAt(clock.now()); objections.save(objection); party.setStatus(PartyStatus.REVIEW_PENDING);
    }

    /** 보관 기간이 지난 사진과 계좌를 지운다. 금액과 정산 기록은 남긴다. 일행별 짧은 트랜잭션으로 락 경합을 줄인다. */
    public void purgeReceipts() {
        Instant now = clock.now();
        Instant limit = now.minus(MediaRetention.KEEP);
        List<Long> partyIds = settlements.findExpired(limit).stream()
                .map(s -> s.getParty().getId()).distinct().sorted().toList();
        var tx = new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (long partyId : partyIds) {
            tx.executeWithoutResult(status -> {
                Party party = parties.findByIdForUpdate(partyId).orElse(null);
                if (party == null) return;
                Settlement settlement = settlements.findByParty(party).orElse(null);
                if (settlement == null) return;
                entityManager.refresh(settlement);
                if (!MediaRetention.receiptExpired(settlement, clock.now())) return;
                media.deleteAfterCommit(settlement.getReceiptPath());
                settlement.setReceiptPath(null);
                settlement.setReceiptContentType(null);
                settlement.clearAccount();
            });
        }
    }

    public boolean receiptAvailable(Settlement settlement) {
        return settlement != null && !MediaRetention.receiptExpired(settlement, clock.now())
                && media.readable(settlement.getReceiptPath());
    }

    /** 일행 잠금을 트랜잭션의 첫 읽기로 잡아야 최신 값을 본다. 그래서 일행 번호는 트랜잭션 밖에서 찾는다. */
    public void markSent(long memberId, long transferId, int revision) {
        long partyId = partyIdOf(transferId);
        new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Party locked = parties.findByIdForUpdate(partyId).orElseThrow();
            if (locked.paymentFlow()) throw ApiException.conflict("PAYMENT_FLOW", "새 일행은 결제 승인으로 처리합니다.");
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
        new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Party party = parties.findByIdForUpdate(partyId).orElseThrow();
            if (party.paymentFlow()) throw ApiException.conflict("PAYMENT_FLOW", "새 일행은 결제 승인으로 처리합니다.");
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
                party.setSettledAt(clock.now());
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
        // v2 선결제 흐름(S43)은 확정 금액을, v3 후불 정산과 기존 송금은 실제 미터 요금을 구간 비율로 나눈다.
        long base = party.paymentFlow() && !party.postpaidFlow() ? party.getExpectedTotal() : amount;
        return SettlementCalculator.shares(base, units, payerIndex);
    }

    private static long payerShareOf(Party party, List<RideRequest> riders, List<Long> shares) {
        for (int i = 0; i < riders.size(); i++) {
            if (riders.get(i).getMember().getId().equals(party.getPayer().getId())) {
                return shares.get(i);
            }
        }
        return 0;
    }

    /** 실제 요금이 확정 금액의 1.5배를 넘거나 절반 미만(또는 결제자 확정 몫 미만)이면 관리자가 영수증을 본다(사고·우회·입력 실수). */
    static boolean exceptionalFare(Party party, long amount, long payerShare) {
        return party.paymentFlow()
                && (amount * 2 > party.getExpectedTotal() * 3
                    || amount * 2 < party.getExpectedTotal()
                    || amount < payerShare);
    }

    private record StoredReceipt(String path, String contentType) {
    }

    /** 브라우저가 보낸 형식은 믿지 않고 파일 앞부분으로 확인해 실제 형식을 돌려준다. */
    private static String sniffImageType(MultipartFile receipt) {
        byte[] head = new byte[12];
        try (InputStream in = receipt.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return "image/jpeg";
            }
            if (read >= 8 && Arrays.equals(Arrays.copyOf(head, 8), PNG)) {
                return "image/png";
            }
            if (read >= 12 && new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
                return "image/webp";
            }
            throw ApiException.badRequest("RECEIPT_TYPE", "JPG, PNG, WEBP 사진만 올릴 수 있습니다.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StoredReceipt store(MultipartFile receipt) {
        if (receipt == null || receipt.isEmpty()) {
            throw ApiException.badRequest("RECEIPT_REQUIRED", "결제 내역 사진을 올려 주세요.");
        }
        if (receipt.getContentType() == null || !RECEIPT_TYPES.contains(receipt.getContentType())) {
            throw ApiException.badRequest("RECEIPT_TYPE", "JPG, PNG, WEBP 사진만 올릴 수 있습니다.");
        }
        String detected = sniffImageType(receipt);
        return new StoredReceipt(media.storeReceipt(receipt), detected);
    }
}
