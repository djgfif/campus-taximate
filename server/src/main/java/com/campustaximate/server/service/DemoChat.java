package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 시연에서 샘플 회원이 남기는 채팅. 분위기용이라 실패해도 시연 동작(결제, 도착, 정산)을 멈추지 않는다.
 * 예: 정산 확정 뒤 채팅이 읽기 전용(CHAT_CLOSED)일 때 추가 결제 승인 뒤 인사를 남기지 못해도 다음 샘플 결제는 이어 간다.
 * 호출하는 시연 서비스는 트랜잭션 밖에서 부르므로, 채팅 트랜잭션만 롤백되고 앞선 처리는 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DemoChat {
    private final PartyChatService chat;

    void send(long memberId, long partyId, String text) {
        try {
            chat.send(memberId, partyId, text);
        } catch (ApiException e) {
            log.debug("시연 채팅 생략. party={} member={} reason={}", partyId, memberId, e.getMessage());
        }
    }
}
