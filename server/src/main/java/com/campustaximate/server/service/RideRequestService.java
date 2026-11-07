package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.TripPath;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.School;
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
import java.util.function.Supplier;
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
    private static final Duration DEPARTURE_MARGIN = Duration.ofMinutes(15);
    private static final Duration CONFIRM_BEFORE = Duration.ofMinutes(60);
    private static final Duration SOLO_LOOKUP_BEFORE = Duration.ofMinutes(60);

    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final TransferRequestRepository transfers;
    private final SoloRouteLookup soloRoutes;
    private final PartyShrinker shrinker;
    private final PlatformTransactionManager transactionManager;
    private final AppClock clock;
    private final MemberSafetyService safety;

    /** 하교는 hubId, windowStart, windowEnd를, 등교는 arriveBy를 쓴다. 등교의 학교는 회원의 학교다. */
    public record NewRequest(Direction direction, Long hubId, Instant windowStart, Instant windowEnd, Instant arriveBy,
                             String pointName, double pointLat, double pointLon, String memo) {
        GeoPoint point() {
            return new GeoPoint(pointLat, pointLon);
        }
    }

    public record Preview(long soloFare, int soloMinutes, Instant resultBy) {
    }

    /** 카카오 호출은 트랜잭션 밖에서 한다. 회원당 1개는 저장할 때 유일 제약이 다시 막는다. */
    public RideRequest create(long memberId, NewRequest input) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        GeoPoint anchor = tx.execute(status -> {
            members.findByIdForUpdate(memberId).orElseThrow(ApiException::unauthorized);
            safety.requireUnrestricted(memberId);
            GeoPoint point = validateTrip(memberId, input);
            if (isTransferRestricted(memberId)) {
                throw ApiException.conflict("TRANSFER_OVERDUE", "24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없습니다.");
            }
            if (requests.findByActiveMemberId(memberId).isPresent()) {
                throw ApiException.conflict("ACTIVE_REQUEST", "이미 진행 중인 요청이 있습니다.");
            }
            return point;
        });
        RouteClient.Route solo = soloRoute(input, () -> soloRoutes.route(soloPath(anchor, input), lookupTime(input)));
        Instant deadlineSlot = deadlineSlot(input, solo);
        return tx.execute(status -> save(memberId, input, solo, deadlineSlot));
    }

    /** 요청 전에 단독 요금과 결과 안내 시각을 보여 준다. 저장하지 않는다. */
    public Preview preview(long memberId, NewRequest input) {
        GeoPoint anchor = new TransactionTemplate(transactionManager).execute(status -> validateTrip(memberId, input));
        RouteClient.Route solo = soloRoute(input,
                () -> soloRoutes.preview(memberId, soloPath(anchor, input), lookupTime(input)));
        Instant deadlineSlot = deadlineSlot(input, solo);
        Instant resultBy = deadlineSlot != null ? deadlineSlot : input.windowEnd().minus(LEAD);
        return new Preview(solo.fare(), (solo.seconds() + 59) / 60, resultBy);
    }

    private static TripPath soloPath(GeoPoint anchor, NewRequest input) {
        return TripPlan.of(input.direction()).soloPath(anchor, input.point());
    }

    private static Instant lookupTime(NewRequest input) {
        return input.arriveBy() == null ? null : input.arriveBy().minus(SOLO_LOOKUP_BEFORE);
    }

    private RouteClient.Route soloRoute(NewRequest input, Supplier<RouteClient.Route> lookup) {
        try {
            return lookup.get();
        } catch (RouteClient.RouteUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_UNAVAILABLE", "경로를 조회하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    /** 예상 출발 60분 전을 넘지 않는 마지막 슬롯. 하교는 없다. 이미 지났으면 요청을 받지 않는다. */
    private Instant deadlineSlot(NewRequest input, RouteClient.Route solo) {
        if (input.direction() != Direction.TO_SCHOOL) {
            return null;
        }
        Instant expected = input.arriveBy().minusSeconds(solo.seconds()).minus(DEPARTURE_MARGIN);
        Instant slot = AppClock.slotOf(expected.minus(CONFIRM_BEFORE));
        if (slot.isBefore(AppClock.nextSlotAfter(clock.now()))) {
            throw ApiException.badRequest("TOO_LATE", "도착 희망 시각이 너무 가까워 일행을 찾을 시간이 없습니다.");
        }
        return slot;
    }

    /** 공통 지점을 돌려준다. */
    private GeoPoint validateTrip(long memberId, NewRequest input) {
        Member member = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        if (!member.hasProfile()) {
            throw ApiException.badRequest("PROFILE_REQUIRED", "닉네임과 성별을 먼저 입력해 주세요.");
        }
        if (input.direction() == Direction.TO_SCHOOL) {
            validateArrival(input.arriveBy());
            School school = member.getSchool();
            return new GeoPoint(school.getLat(), school.getLon());
        }
        Hub hub = input.hubId() == null ? null : hubs.findById(input.hubId())
                .filter(h -> h.getSchools().contains(member.getSchool()))
                .orElse(null);
        if (hub == null) {
            throw ApiException.badRequest("HUB_NOT_FOUND", "없는 거점입니다.");
        }
        validateWindow(input.windowStart(), input.windowEnd());
        return new GeoPoint(hub.getLat(), hub.getLon());
    }

    private RideRequest save(long memberId, NewRequest input, RouteClient.Route solo, Instant deadlineSlot) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiException::unauthorized);
        safety.requireUnrestricted(memberId);
        if (isTransferRestricted(memberId)) {
            throw ApiException.conflict("TRANSFER_OVERDUE", "24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없어요.");
        }
        RideRequest request = new RideRequest();
        request.setMember(member);
        request.setActiveMemberId(memberId);
        request.setDirection(input.direction());
        if (input.direction() == Direction.TO_SCHOOL) {
            request.setSchool(member.getSchool());
            request.setArriveBy(input.arriveBy());
            request.setDeadlineSlot(deadlineSlot);
        } else {
            request.setHub(hubs.getReferenceById(input.hubId()));
            request.setWindowStart(input.windowStart());
            request.setWindowEnd(input.windowEnd());
        }
        request.setPointName(input.pointName());
        request.setPointLat(input.pointLat());
        request.setPointLon(input.pointLon());
        request.setMemo(input.memo());
        request.setSoloKm(solo.km());
        request.setSoloSeconds(solo.seconds());
        request.setSoloFare(solo.fare());
        return requests.saveAndFlush(request);
    }

    /** 대기 중이면 요청만 취소하고, 매칭됐으면 그 사람만 일행에서 뺀다. */
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
            if (!request.canCancelAt(clock.now())) {
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
        return safety.activeRestriction(memberId) != null || isTransferRestricted(memberId);
    }

    @Transactional(readOnly = true)
    public boolean isTransferRestricted(long memberId) {
        return transfers.existsOverdue(memberId, TransferStatus.REQUESTED, clock.now().minus(OVERDUE));
    }

    private void validateArrival(Instant arriveBy) {
        if (arriveBy == null || !isFiveMinuteUnit(arriveBy)) {
            throw ApiException.badRequest("INVALID_ARRIVAL", "도착 희망 시각은 5분 단위로만 고를 수 있습니다.");
        }
        if (!clock.now().isBefore(arriveBy)) {
            throw ApiException.badRequest("TOO_LATE", "이미 지난 시각입니다.");
        }
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            throw ApiException.badRequest("INVALID_WINDOW", "출발 가능 시간을 입력해 주세요.");
        }
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
