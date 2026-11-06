package com.campustaximate.server.service;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.TransferStatus;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.server.route.RouteClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RideRequestService {

    private static final Duration MAX_WINDOW = Duration.ofMinutes(60);
    private static final Duration LEAD = Duration.ofMinutes(30);
    private static final Duration OVERDUE = Duration.ofHours(24);

    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final TransferRequestRepository transfers;
    private final RouteClient routes;
    private final PartyShrinker shrinker;
    private final PlatformTransactionManager transactionManager;
    private final AppClock clock;

    public record NewRequest(long hubId, Instant windowStart, Instant windowEnd, String dropoffName,
                             double dropoffLat, double dropoffLon, String memo) {
    }

    /** 카카오 호출은 트랜잭션 밖에서 한다. 회원당 1개는 저장할 때 유일 제약이 다시 막는다. */
    public RideRequest create(long memberId, NewRequest input) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        GeoPoint hubPoint = tx.execute(status -> validate(memberId, input));
        RouteClient.Route solo;
        try {
            solo = routes.route(hubPoint, List.of(new GeoPoint(input.dropoffLat(), input.dropoffLon())));
        } catch (RouteClient.RouteUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_UNAVAILABLE", "경로를 조회하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        return tx.execute(status -> save(memberId, input, solo));
    }

    private GeoPoint validate(long memberId, NewRequest input) {
        Member member = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        if (!member.hasProfile()) {
            throw ApiException.badRequest("PROFILE_REQUIRED", "닉네임과 성별을 먼저 입력해 주세요.");
        }
        Hub hub = hubs.findById(input.hubId())
                .filter(h -> h.getSchools().contains(member.getSchool()))
                .orElseThrow(() -> ApiException.badRequest("HUB_NOT_FOUND", "없는 거점입니다."));
        validateWindow(input.windowStart(), input.windowEnd());
        if (isRestricted(memberId)) {
            throw ApiException.conflict("TRANSFER_OVERDUE", "24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없습니다.");
        }
        if (requests.findByActiveMemberId(memberId).isPresent()) {
            throw ApiException.conflict("ACTIVE_REQUEST", "이미 진행 중인 요청이 있습니다.");
        }
        return new GeoPoint(hub.getLat(), hub.getLon());
    }

    private RideRequest save(long memberId, NewRequest input, RouteClient.Route solo) {
        RideRequest request = new RideRequest();
        request.setMember(members.getReferenceById(memberId));
        request.setActiveMemberId(memberId);
        request.setHub(hubs.getReferenceById(input.hubId()));
        request.setWindowStart(input.windowStart());
        request.setWindowEnd(input.windowEnd());
        request.setDropoffName(input.dropoffName());
        request.setDropoffLat(input.dropoffLat());
        request.setDropoffLon(input.dropoffLon());
        request.setMemo(input.memo());
        request.setSoloKm(solo.km());
        request.setSoloSeconds(solo.seconds());
        request.setSoloFare(solo.fare());
        return requests.saveAndFlush(request);
    }

    /** 대기 중이면 요청만 취소하고, 묶였으면 그 사람만 일행에서 뺀다. */
    public void cancel(long memberId, long requestId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long partyId = tx.execute(status -> {
            RideRequest request = requests.findById(requestId)
                    .filter(r -> r.getMember().getId() == memberId)
                    .orElseThrow(() -> ApiException.notFound("없는 요청입니다."));
            if (request.getStatus() == RequestStatus.WAITING) {
                request.finish(RequestStatus.CANCELED);
                return null;
            }
            if (request.getStatus() != RequestStatus.MATCHED) {
                throw ApiException.conflict("CANNOT_CANCEL", "취소할 수 없는 상태입니다.");
            }
            if (!clock.now().isBefore(request.getParty().getDepartureAt())) {
                throw ApiException.conflict("DEPARTED", "출발 시각이 지나 취소할 수 없습니다.");
            }
            return request.getParty().getId();
        });
        if (partyId != null) {
            shrinker.remove(partyId, Set.of(requestId), clock.now(), false);
        }
    }

    @Transactional(readOnly = true)
    public boolean isRestricted(long memberId) {
        return transfers.existsOverdue(memberId, TransferStatus.REQUESTED, clock.now().minus(OVERDUE));
    }

    private void validateWindow(Instant start, Instant end) {
        if (!isFiveMinuteUnit(start) || !isFiveMinuteUnit(end)) {
            throw ApiException.badRequest("INVALID_WINDOW", "출발 가능 시간은 5분 단위로만 고를 수 있습니다.");
        }
        if (end.isBefore(start) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw ApiException.badRequest("INVALID_WINDOW", "출발 가능 시간은 최대 60분입니다.");
        }
        if (!clock.now().isBefore(end.minus(LEAD))) {
            throw ApiException.badRequest("TOO_LATE", "요청은 끝 시각 30분 전까지만 받습니다.");
        }
    }

    private static boolean isFiveMinuteUnit(Instant time) {
        return time.equals(time.truncatedTo(ChronoUnit.MINUTES))
                && time.getEpochSecond() / 60 % AppClock.SLOT_MINUTES == 0;
    }
}
