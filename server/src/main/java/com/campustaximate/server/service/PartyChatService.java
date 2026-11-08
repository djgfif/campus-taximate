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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartyChatService {
    private static final Duration CHAT_LIMIT = Duration.ofHours(24);
    private static final Duration KEEP = Duration.ofDays(7);
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyMessageRepository messages;
    private final AppClock clock;
    private final ChangeBus changes;
    private final PushService push;

    public record MessageView(long id, long memberId, String nickname, String text, Instant createdAt, boolean mine) { }
    public record ChatView(List<MessageView> messages, boolean closed, Instant closesAt) { }

    /** 일행 잠금을 첫 읽기로 잡아 취소/정산 완료와 권한 판정을 직렬화한다. */
    @Transactional
    public ChatView read(long actor, long partyId, Long after) {
        Party party = lock(partyId);
        requireMember(actor, party);
        if (after != null && after < 0) throw ApiException.badRequest("INVALID_AFTER", "메시지 번호를 확인해 주세요.");
        List<PartyMessage> rows;
        if (after == null) {
            rows = new ArrayList<>(messages.findTop50ByPartyIdOrderByIdDesc(partyId));
            Collections.reverse(rows);
        } else {
            rows = messages.findTop50ByPartyIdAndIdGreaterThanOrderByIdAsc(partyId, after);
        }
        Instant end = endsAt(party);
        // 스케줄러가 다음에 돌기 전에도 보관 기간이 지난 내용은 내려주지 않는다.
        List<MessageView> views = expired(party) ? List.of() : rows.stream().map(m -> view(m, actor)).toList();
        return new ChatView(views, closed(party), end);
    }

    @Transactional
    public MessageView send(long actor, long partyId, String text) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        RideRequest mine = riders.stream().filter(r -> r.getMember().getId() == actor).findFirst()
                .orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
        if (closed(party)) throw ApiException.conflict("CHAT_CLOSED", "일행 채팅은 읽기만 가능해요.");
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.badRequest("INVALID_MESSAGE", "메시지는 1~200자로 입력해 주세요.");
        }
        PartyMessage saved = messages.save(new PartyMessage(party, mine.getMember(), trimmed, clock.now().truncatedTo(ChronoUnit.MICROS)));
        String preview = trimmed.substring(0, trimmed.offsetByCodePoints(0, Math.min(40, trimmed.codePointCount(0, trimmed.length()))));
        riders.stream().map(r -> r.getMember().getId()).distinct().filter(id -> id != actor).forEach(id -> {
            changes.memberChanged(id);
            push.send(id, "[캠퍼스 택시메이트] 일행 메시지", preview, "/");
        });
        return view(saved, actor);
    }

    @Transactional
    public void purgeMessages() {
        for (long partyId : messages.findPartyIdsWithMessages()) {
            Party party = lock(partyId);
            if (expired(party)) messages.deleteForParty(partyId);
        }
    }

    private Party lock(long partyId) {
        return parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
    }

    private List<RideRequest> currentMembers(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(r -> !r.isNoShow() && (r.getStatus() == RequestStatus.MATCHED || r.getStatus() == RequestStatus.COMPLETED))
                .toList();
    }

    private void requireMember(long actor, Party party) {
        if (currentMembers(party).stream().noneMatch(r -> r.getMember().getId() == actor)) {
            throw ApiException.forbidden("현재 일행원만 채팅할 수 있어요.");
        }
    }

    private Instant endsAt(Party party) {
        Instant deadline = party.getDepartureAt().plus(CHAT_LIMIT);
        Instant settled = party.getSettledAt();
        return settled != null && settled.isBefore(deadline) ? settled : deadline;
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
