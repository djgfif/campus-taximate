package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.MemberAvoid;
import com.campustaximate.server.domain.MemberReport;
import com.campustaximate.server.domain.MemberRestriction;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyAssignment;
import com.campustaximate.server.domain.ReportReason;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.MemberAvoidRepository;
import com.campustaximate.server.repository.MemberReportRepository;
import com.campustaximate.server.repository.MemberRestrictionRepository;
import com.campustaximate.server.repository.PartyAssignmentRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.MemberRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회피 방향은 저장한 사람의 소유다. 매칭 시에만 양방향으로 해석한다. 상대에게 알리지 않는다. */
@Service
@RequiredArgsConstructor
public class MemberSafetyService {
    private final MemberAvoidRepository avoids;
    private final MemberReportRepository reports;
    private final MemberRestrictionRepository restrictions;
    private final PartyAssignmentRepository assignments;
    private final PartyRepository parties;
    private final PartyChatService chat;
    private final PartyMessageRepository messages;
    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final AppClock clock;
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
            .withZone(ZoneId.of("Asia/Seoul"));

    public record Peer(long memberId, String nickname, boolean avoided) { }
    public record Restriction(String reason, Instant expiresAt, String message) { }

    static final String NO_SHOW_REASON = "NO_SHOW_REPEATED";

    /** 슬롯 확정 트랜잭션 안에서 배정 사실을 보존한다. 취소돼도 함께 배정됐던 권한은 남는다. */
    public void remember(Party party, List<RideRequest> riders) {
        for (RideRequest rider : riders) {
            PartyAssignment assignment = new PartyAssignment();
            assignment.setParty(party);
            assignment.setMemberId(rider.getMember().getId());
            assignment.setRequestId(rider.getId());
            assignments.save(assignment);
        }
    }

    /** 회원 잠금 순서를 통일해 신고/회피와 슬롯 확정이 동시에 와도 교착하지 않는다. */
    public void lockMembers(Collection<Long> ids) {
        ids.stream().distinct().sorted().forEach(id -> members.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.badRequest("MEMBER_NOT_FOUND", "없는 회원이에요.")));
    }

    @Transactional
    public void avoid(long actor, long target) {
        requireOther(actor, target);
        lockMembers(List.of(actor, target));
        requireFormerParty(actor, target);
        addAvoid(actor, target);
    }

    @Transactional
    public void unavoid(long actor, long target) {
        requireOther(actor, target);
        lockMembers(List.of(actor, target));
        requireFormerParty(actor, target);
        avoids.deleteByMemberIdAndAvoidedMemberId(actor, target);
    }

    private void addAvoid(long actor, long target) {
        if (!avoids.existsByMemberIdAndAvoidedMemberId(actor, target)) {
            MemberAvoid avoid = new MemberAvoid();
            avoid.setMemberId(actor);
            avoid.setAvoidedMemberId(target);
            avoid.setCreatedAt(clock.now());
            avoids.save(avoid);
        }
    }

    @Transactional
    public void report(long actor, long target, long partyId, ReportReason reason, String memo) {
        report(actor, target, partyId, reason, memo, null);
    }

    @Transactional
    public void report(long actor, long target, long partyId, ReportReason reason, String memo, Long messageId) {
        requireOther(actor, target);
        if (reason == null || (memo != null && memo.length() > 200)) {
            throw ApiException.badRequest("INVALID_REPORT", "신고 사유와 200자 이하의 메모를 입력해 주세요.");
        }
        lockMembers(List.of(actor, target));
        requirePartyMember(partyId, actor);
        requirePartyMember(partyId, target);
        if (reports.existsByReporterIdAndReportedIdAndPartyId(actor, target, partyId)) {
            throw ApiException.conflict("ALREADY_REPORTED", "이 일행에서 이미 신고한 상대예요.");
        }
        long before = reports.countReportedParties(target);
        MemberReport report = new MemberReport();
        report.setReporterId(actor);
        report.setReportedId(target);
        report.setParty(parties.getReferenceById(partyId));
        report.setReason(reason);
        report.setMemo(memo == null || memo.isBlank() ? null : memo.trim());
        if (messageId != null) {
            var evidence = messages.findById(messageId)
                    .filter(m -> m.getParty().getId() == partyId && m.getMember().getId() == target)
                    .orElseThrow(() -> ApiException.badRequest("INVALID_MESSAGE", "해당 일행원의 메시지가 아니에요."));
            report.setMessageId(evidence.getId());
            report.setMessageSnapshot(evidence.getText());
        }
        report.setCreatedAt(clock.now());
        reports.saveAndFlush(report);
        addAvoid(actor, target);
        long after = reports.countReportedParties(target);
        if (after > before && after >= ReportPolicy.PARTY_THRESHOLD && activeRestriction(target) == null) {
            MemberRestriction restriction = new MemberRestriction();
            restriction.setMemberId(target);
            restriction.setReason("REPORT_ACCUMULATION");
            Instant now = clock.now();
            restriction.setCreatedAt(now);
            restriction.setExpiresAt(now.plus(ReportPolicy.RESTRICTION_DURATION));
            restrictions.save(restriction);
        }
    }

    /** 노쇼를 기록하고, 최근 30일 노쇼가 반복되면 새 요청을 제한한다. 이미 더 긴 제한이 있으면 새로 만들지 않는다. */
    public void recordNoShow(RideRequest rider, Instant now) {
        if (rider.isNoShow()) {
            return;
        }
        rider.markNoShow(now);
        if (rider.getParty() != null) {
            chat.markLeft(rider.getParty().getId(), rider.getMember().getId(), now);
        }
        Member member = rider.getMember();
        member.setNoShowCount(member.getNoShowCount() + 1);
        requests.flush();
        long recent = requests.countByMemberIdAndNoShowTrueAndNoShowAtAfter(member.getId(), now.minus(NoShowPolicy.WINDOW));
        java.time.Duration duration = NoShowPolicy.restrictionFor(recent);
        if (duration == null) {
            return;
        }
        Instant until = now.plus(duration);
        boolean covered = restrictions.findFirstByMemberIdAndExpiresAtGreaterThanOrderByExpiresAtDesc(member.getId(), now)
                .map(r -> !r.getExpiresAt().isBefore(until))
                .orElse(false);
        if (covered) {
            return;
        }
        MemberRestriction restriction = new MemberRestriction();
        restriction.setMemberId(member.getId());
        restriction.setReason(NO_SHOW_REASON);
        restriction.setCreatedAt(now);
        restriction.setExpiresAt(until);
        restrictions.save(restriction);
    }

    private static void requireOther(long actor, long target) {
        if (actor == target) throw ApiException.badRequest("SELF_TARGET", "본인은 지정할 수 없어요.");
    }

    private void requireFormerParty(long actor, long target) {
        if (!assignments.sharedParty(actor, target) && !requests.shareParty(actor, target)) {
            throw ApiException.forbidden("함께 배정됐던 일행원만 지정할 수 있어요.");
        }
    }

    private void requirePartyMember(long partyId, long memberId) {
        if (!assignments.existsByPartyIdAndMemberId(partyId, memberId)
                && !requests.findMemberIdsByPartyId(partyId).contains(memberId)) {
            throw ApiException.forbidden("함께 배정됐던 일행원만 신고할 수 있어요.");
        }
    }

    @Transactional(readOnly = true)
    public List<Long> partyIds(long requestId) {
        return assignments.findByRequestIdOrderById(requestId).stream().map(a -> a.getParty().getId()).toList();
    }

    @Transactional(readOnly = true)
    public List<Peer> peers(long actor, long partyId) {
        requirePartyMember(partyId, actor);
        Set<Long> ids = new LinkedHashSet<>();
        assignments.findByPartyId(partyId).forEach(a -> ids.add(a.getMemberId()));
        ids.addAll(requests.findMemberIdsByPartyId(partyId));
        ids.remove(actor);
        Set<Long> mine = new HashSet<>();
        avoids.findByMemberId(actor).forEach(a -> mine.add(a.getAvoidedMemberId()));
        return members.findAllById(ids).stream()
                .map(m -> new Peer(m.getId(), m.getNickname(), mine.contains(m.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<Peer> avoids(long actor) {
        return avoids.findByMemberId(actor).stream().map(a -> {
            Member m = members.findById(a.getAvoidedMemberId()).orElseThrow();
            return new Peer(m.getId(), m.getNickname(), true);
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> avoidMap(Collection<Long> ids) {
        Map<Long, Set<Long>> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (MemberAvoid a : avoids.findForMembers(ids)) {
                result.computeIfAbsent(a.getMemberId(), k -> new HashSet<>()).add(a.getAvoidedMemberId());
                result.computeIfAbsent(a.getAvoidedMemberId(), k -> new HashSet<>()).add(a.getMemberId());
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Restriction activeRestriction(long memberId) {
        return restrictions.findFirstByMemberIdAndExpiresAtGreaterThanOrderByExpiresAtDesc(memberId, clock.now())
                .map(r -> new Restriction(r.getReason(), r.getExpiresAt(),
                        (NO_SHOW_REASON.equals(r.getReason()) ? "노쇼가 반복되어 " : "신고 누적으로 ")
                                + UNTIL.format(r.getExpiresAt()) + "까지 새 요청을 할 수 없어요"))
                .orElse(null);
    }

    public void requireUnrestricted(long memberId) {
        Restriction restriction = activeRestriction(memberId);
        if (restriction != null) throw ApiException.conflict("REPORT_RESTRICTED", restriction.message());
    }
}
