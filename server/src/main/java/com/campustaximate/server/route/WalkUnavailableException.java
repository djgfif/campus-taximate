package com.campustaximate.server.route;

import com.campustaximate.server.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * TMAP 보행 경로를 받지 못했을 때(오류, 시간 초과, 일일 한도). 걷는 시간을 모르면 지연 15분을
 * 계산할 수 없으므로 추정값으로 대신하지 않고 요청을 받지 않는다(결정 S44, 2026-11-14).
 */
public class WalkUnavailableException extends ApiException {
    public WalkUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "WALK_UNAVAILABLE", "걷는 시간을 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
    }
}
