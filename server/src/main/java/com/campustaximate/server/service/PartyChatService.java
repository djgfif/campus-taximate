package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartyChatService {
    private static final Duration CHAT_LIMIT = Duration.ofHours(24);
    private static final Duration KEEP = Duration.ofDays(7);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_MESSAGES_PER_MINUTE = 10;

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyAssignmentRepository assignments;
    private final PartyMessageRepository messages;
    private final AppClock clock;
    private final ChangeBus changes;
    private final PushService push;

    public record MessageView(long id, long memberId, String nickname, String text, Instant createdAt, boolean mine) { }
    public record ChatView(List<MessageView> messages, boolean closed, Instant closesAt,
                           boolean hasOlder, boolean hasNewer) { }

    /** Keep the old API signature for existing clients and tests. */
    @Transactional
    public ChatView read(long actor, long partyId, Long after) {
        return read(actor, partyId, after, null);
    }

    /**
     * after: newer messages in ascending order; before: older messages in ascending order;
     * neither: latest page. The leaving member's visibility ceiling applies to every query.
     */
    @Transactional
    public ChatView read(long actor, long partyId, Long after, Long before) {
        Party party = lock(partyId);
        if (after != null && before != null) {
            throw ApiException.badRequest("INVALID_CURSOR", "이전/이후 메시지는 동시에 조회할 수 없어요.");
        }
        if (after != null && after < 0 || before != null && before <= 0) {
            throw ApiException.badRequest("INVALID_CURSOR", "메시지 번호를 확인해 주세요.");
        }
        PartyAssignment membership = assignments.findByPartyIdAndMemberId(partyId, actor)
                .orElseThrow(() -> ApiException.forbidden("이 일행의 채팅을 볼 수 없어요."));
        boolean current = currentMembers(party).stream().anyMatch(r -> r.getMember().getId() == actor);
        if (!current && membership.getLeftAt() == null && party.getStatus() != PartyStatus.CANCELED) {
            throw ApiException.forbidden("이 일행의 채팅을 볼 수 없어요.");
        }
        long ceiling = membership.getLeftAt() == null ? Long.MAX_VALUE
                : membership.getLastVisibleMessageId() == null ? 0L : membership.getLastVisibleMessageId();
        boolean hasOlder = false;
        boolean hasNewer = false;
        List<PartyMessage> rows;
        if (after != null) {
            rows = messages.findTop51ByPartyIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                    partyId, after, ceiling);
            hasNewer = rows.size() > PAGE_SIZE;
        } else {
            long bound = before == null ? ceiling : Math.min(ceiling, before - 1);
            rows = messages.findTop51ByPartyIdAndIdLessThanEqualOrderByIdDesc(partyId, bound);
            hasOlder = rows.size() > PAGE_SIZE;
        }
        List<PartyMessage> page = new ArrayList<>(rows.subList(0, Math.min(PAGE_SIZE, rows.size())));
        if (after == null) Collections.reverse(page);
        if (expired(party)) {
            page.clear();
            hasOlder = false;
            hasNewer = false;
        }
        return new ChatView(page.stream().map(m -> view(m, actor)).toList(),
                closed(party) || membership.getLeftAt() != null || !current,
                endsAt(party), hasOlder, hasNewer);
    }

    @Transactional
    public MessageView send(long actor, long partyId, String text) {
        return send(actor, partyId, text, null);
    }

    @Transactional
    public MessageView send(long actor, long partyId, String text, String clientMessageId) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        RideRequest mine = riders.stream().filter(r -> r.getMember().getId() == actor).findFirst()
                .orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.badRequest("INVALID_MESSAGE", "메시지는 1~200자로 입력해 주세요.");
        }
        if (clientMessageId != null && !clientMessageId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw ApiException.badRequest("INVALID_MESSAGE_ID", "메시지 전송 번호가 올바르지 않아요.");
        }
        if (clientMessageId != null) {
            PartyMessage prior = messages.findByPartyIdAndMemberIdAndClientMessageId(partyId, actor, clientMessageId)
                    .orElse(null);
            if (prior != null) {
                if (!prior.getText().equals(trimmed)) {
                    throw ApiException.conflict("MESSAGE_ID_REUSED", "이 메시지 전송 번호는 이미 사용됐어요.");
                }
                return view(prior, actor);
            }
        }
        if (closed(party)) throw ApiException.conflict("CHAT_CLOSED", "일행 채팅은 읽기만 가능해요.");
        Instant now = clock.now();
        if (messages.countByPartyIdAndMemberIdAndCreatedAtGreaterThanEqual(
                partyId, actor, now.minusSeconds(60)) >= MAX_MESSAGES_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMIT",
                    "메시지를 너무 빠르게 보내고 있어요. 잠시 뒤 다시 시도해 주세요.");
        }
        PartyMessage saved = messages.save(new PartyMessage(
                party, mine.getMember(), trimmed, now.truncatedTo(ChronoUnit.MICROS), clientMessageId));
        List<Long> recipients = riders.stream().map(r -> r.getMember().getId()).distinct().toList();
        changes.partyMessageChanged(partyId, saved.getId(), recipients);
        recipients.stream().filter(id -> id != actor).forEach(id ->
                push.send(id, "[캠퍼스 택시메이트] 일행 메시지",
                        "새 일행 메시지가 도착했어요.", "/"));
        return view(saved, actor);
    }

    /** Invoked while the group's write lock is held, within the same database transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markLeft(long partyId, long memberId, Instant at) {
        assignments.findByPartyIdAndMemberId(partyId, memberId).ifPresent(membership -> {
            if (membership.getLeftAt() == null) {
                membership.leave(at, messages.latestId(partyId));
            }
        });
    }

    @Transactional
    public void purgeMessages() {
        for (long partyId : messages.findPartyIdsWithMessages()) {
            Party party = lock(partyId);
            if (expired(party)) messages.deleteForParty(partyId);
        }
    }

    private Party lock(long partyId) {
        return parties.findByIdForUpdate(partyId)
                .orElseThrow(() -> ApiException.forbidden("이 일행의 채팅을 볼 수 없어요."));
    }

    private List<RideRequest> currentMembers(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(r -> !r.isNoShow()
                        && (r.getStatus() == RequestStatus.MATCHED || r.getStatus() == RequestStatus.COMPLETED))
                .toList();
    }

    private Instant endsAt(Party party) {
        Instant deadline = party.getDepartureAt().plus(CHAT_LIMIT);
        if (party.getSettledAt() != null && party.getSettledAt().isBefore(deadline)) {
            deadline = party.getSettledAt();
        }
        if (party.getChatClosedAt() != null && party.getChatClosedAt().isBefore(deadline)) {
            deadline = party.getChatClosedAt();
        }
        return deadline;
    }

    private boolean closed(Party party) {
        return party.getStatus() == PartyStatus.COMPLETED || party.getStatus() == PartyStatus.CANCELED
                || !clock.now().isBefore(endsAt(party));
    }

    private boolean expired(Party party) {
        return !clock.now().isBefore(endsAt(party).plus(KEEP));
    }

    private MessageView view(PartyMessage message, long actor) {
        return new MessageView(message.getId(), message.getMember().getId(), message.getMember().getNickname(),
                message.getText(), message.getCreatedAt(), message.getMember().getId() == actor);
    }
}
