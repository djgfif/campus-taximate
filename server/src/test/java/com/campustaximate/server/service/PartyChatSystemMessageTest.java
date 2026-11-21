package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.repository.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 시스템 채팅은 실패해도 본래 처리를 막지 않지만, DB 충돌은 삼키지 않는다.
 * 삼키면 같은 트랜잭션이 롤백 전용인 채로 커밋돼 UnexpectedRollbackException이 나고,
 * 매칭 확정의 충돌 처리(dropOnConflict)가 동작하지 않는다(CI MySQL에서 취소와 매칭이 겹칠 때 발생).
 */
class PartyChatSystemMessageTest {
    final PartyMessageRepository messages = mock(PartyMessageRepository.class);
    final RideRequestRepository requests = mock(RideRequestRepository.class);
    final ChangeBus changes = mock(ChangeBus.class);
    final AppClock clock = mock(AppClock.class);
    final PartyChatService chat = new PartyChatService(mock(PartyRepository.class), requests, messages,
            mock(PartyChatReadRepository.class), clock, changes, mock(PushService.class), mock(MemberRepository.class),
            mock(MediaFiles.class), mock(org.springframework.transaction.PlatformTransactionManager.class));
    final Party party = mock(Party.class);

    PartyChatSystemMessageTest() {
        when(clock.now()).thenReturn(Instant.parse("2026-11-19T08:00:00Z"));
        when(party.getId()).thenReturn(14L);
    }

    @Test void concurrentUpdateConflictIsNotSwallowed() {
        when(requests.findMemberIdsByPartyId(14L)).thenThrow(new ObjectOptimisticLockingFailureException("RideRequest", 31L));
        assertThatThrownBy(() -> chat.system(party, "일행이 모였어요."))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test void nonDatabaseFailureDoesNotBlockTheCaller() {
        doThrow(new IllegalStateException("알림 채널 오류")).when(changes).chatChanged(anyLong(), any());
        assertThatCode(() -> chat.system(party, "일행이 모였어요.")).doesNotThrowAnyException();
    }
}
