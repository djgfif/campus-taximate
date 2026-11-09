package com.campustaximate.server.service;

import com.campustaximate.server.repository.RideRequestRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 열린 화면에 "내 상태가 바뀌었다"는 신호만 보낸다. 내용은 화면이 /state로 다시 읽는다. 서버 한 대 기준이다. */
@Component
@RequiredArgsConstructor
public class ChangeBus {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final RideRequestRepository requests;
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "change-bus");
        thread.setDaemon(true);
        return thread;
    });

    public SseEmitter subscribe(long memberId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(memberId, id -> new CopyOnWriteArraySet<>()).add(emitter);
        Runnable remove = () -> remove(memberId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        send(memberId, emitter, SseEmitter.event().name("ready").data("ok"));
        return emitter;
    }

    /** 커밋이 끝난 뒤에 알린다. 롤백된 변경은 알리지 않는다. */
    public void memberChanged(long memberId) {
        afterCommit(() -> notifyMember(memberId));
    }

    public void partyChanged(long partyId) {
        afterCommit(() -> requests.findMemberIdsByPartyId(partyId).forEach(this::notifyMember));
    }

    /** A chat notification contains only the party and message ID; clients fetch authoritative DB rows. */
    public void partyMessageChanged(long partyId, long messageId, List<Long> memberIds) {
        List<Long> recipients = List.copyOf(memberIds);
        afterCommit(() -> recipients.forEach(memberId -> emitters.getOrDefault(memberId, Set.of())
                .forEach(emitter -> send(memberId, emitter, SseEmitter.event().name("party-message")
                        .data(Map.of("partyId", partyId, "messageId", messageId))))));
    }

    private void afterCommit(Runnable action) {
        if (emitters.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.execute(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.execute(action);
            }
        });
    }

    private void notifyMember(long memberId) {
        emitters.getOrDefault(memberId, Set.of())
                .forEach(emitter -> send(memberId, emitter, SseEmitter.event().name("state").data("changed")));
    }

    /** 끊긴 연결을 찾아내고 중간 장비가 연결을 닫지 않게 한다. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        emitters.forEach((memberId, set) -> List.copyOf(set)
                .forEach(emitter -> send(memberId, emitter, SseEmitter.event().comment("ping"))));
    }

    private void send(long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            remove(memberId, emitter);
        }
    }

    private void remove(long memberId, SseEmitter emitter) {
        emitters.computeIfPresent(memberId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
