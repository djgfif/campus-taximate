package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.matching.TripPath;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.route.WalkEstimator;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RideRequestService {
    private static final Duration MAX_WINDOW = Duration.ofMinutes(60);
    private static final Duration LEAD = Duration.ofMinutes(MatchingSettings.everyMinute().leadMinutes());
    private static final Duration OVERDUE = Duration.ofHours(24);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final TransferRequestRepository transfers;
    private final SoloRouteLookup soloRoutes;
    private final WalkEstimator walks;
    private final PartyShrinker shrinker;
    private final MatchingSettings matchingSettings;
    private final com.campustaximate.server.common.InstantMatchingConfiguration instantSettings;
    private final com.campustaximate.server.repository.PartyRepository partyRepository;
    private final PlatformTransactionManager transactionManager;
    private final AppClock clock;
    private final MemberSafetyService safety;
    private final PaymentService payments;
    private final PartyJoinService joins;
    private final com.campustaximate.server.repository.PartyMemberTermRepository partyTerms;
    @Value("${app.features.sequential-pickup:false}")
    private boolean sequentialPickup;

    public record Origin(double lat, double lon, String name) {
        public GeoPoint point() { return new GeoPoint(lat, lon); }
    }

    /** 옛 생성자와 hubId 입력을 함께 유지한다. */
    public record NewRequest(Direction direction, Long hubId, Instant windowStart, Instant windowEnd, Instant arriveBy,
                             String pointName, double pointLat, double pointLon, String memo,
                             RequestMode mode, TimeBasis timeBasis, Origin origin, List<Long> pointIds,
                             Integer earlyArrivalMinutes) {
        public NewRequest(Direction direction, Long hubId, Instant windowStart, Instant windowEnd, Instant arriveBy,
                          String pointName, double pointLat, double pointLon, String memo) {
            this(direction, hubId, windowStart, windowEnd, arriveBy, pointName, pointLat, pointLon, memo,
                    null, null, null, null, null);
        }
        GeoPoint point() { return new GeoPoint(pointLat, pointLon); }
        RequestMode requestMode() { return mode == null ? RequestMode.RESERVATION : mode; }
        TimeBasis basis() {
            return direction == Direction.TO_SCHOOL ? TimeBasis.ARRIVE : timeBasis == null ? TimeBasis.DEPART : timeBasis;
        }
        int earlyMinutes() { return earlyArrivalMinutes == null ? 10 : earlyArrivalMinutes; }
    }

    public record Preview(long soloFare, int soloMinutes, Instant resultBy) { }
    private record PointInput(long hubId, GeoPoint point, int walkSeconds, boolean estimated) { }
    private record Prepared(GeoPoint origin, GeoPoint destination, List<PointInput> points) { }
    private record Quote(RouteClient.Route solo, List<RouteClient.Route> anchors, Instant deadline) { }

    /** 경로 조회는 트랜잭션 밖에서,모든 값의 저장은 한 트랜잭션에서 한다. */
    public RideRequest create(long memberId, NewRequest input) {
        Instant requestedAt = clock.now();
        TransactionTemplate tx = new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager);
        Prepared prepared = tx.execute(status -> {
            members.findByIdForUpdate(memberId).orElseThrow(ApiException::unauthorized);
            requireAvailable(memberId);
            return prepare(memberId, input);
        });
        Quote quote = quote(memberId, input, prepared, false);
        return tx.execute(status -> {
            Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiException::unauthorized);
            requireAvailable(memberId);
            prepare(memberId, input); // 조회 중 지점 비활성화·시간 경과도 다시 검사한다.
            requireTimely(input, quote.solo());
            RideRequest request = new RideRequest();
            request.setMember(member);
            request.setActiveMemberId(memberId);
            request.setDirection(input.direction());
            request.setMode(input.requestMode());
            request.setTimeBasis(input.basis());
            request.setEarlyArrivalMinutes(input.earlyMinutes());
            request.setRequestedAt(requestedAt);
            request.setOriginLat(prepared.origin().lat());
            request.setOriginLon(prepared.origin().lon());
            request.setOriginName(input.origin() == null ? null : input.origin().name());
            if (input.direction() == Direction.TO_SCHOOL) request.setSchool(member.getSchool());
            else request.setHub(hubs.getReferenceById(prepared.points().getFirst().hubId()));
            if(input.requestMode()==RequestMode.INSTANT) {
                // S37: 출발 범위는 입력받지 않는다. 아래 범위는 목록 표시용이며 엔진은 즉시 범위를 보지 않는다.
                request.setWindowStart(requestedAt);
                int walk=prepared.points().stream().mapToInt(PointInput::walkSeconds).max().orElse(0);
                request.setWindowEnd(requestedAt.plusSeconds(matchingSettings.instantMatchMinutes()*60L
                        +Math.max(matchingSettings.instantPrepMinutes()*60L,walk)+matchingSettings.instantDispatchMinutes()*60L));
                request.setDeadlineSlot(requestedAt.plusSeconds(matchingSettings.instantMatchMinutes()*60L));
            } else if (input.basis() == TimeBasis.DEPART) {
                request.setWindowStart(input.windowStart());
                request.setWindowEnd(input.windowEnd());
            } else {
                request.setArriveBy(input.arriveBy());
                request.setDeadlineSlot(quote.deadline());
            }
            request.setPointName(input.pointName());
            request.setPointLat(input.pointLat());
            request.setPointLon(input.pointLon());
            request.setMemo(input.memo());
            request.setSoloKm(quote.solo().km());
            request.setSoloSeconds(quote.solo().seconds());
            request.setSoloFare(quote.solo().fare());
            for (int i = 0; i < prepared.points().size(); i++) {
                PointInput point = prepared.points().get(i);
                RouteClient.Route anchor = quote.anchors().get(i);
                request.getPoints().add(new RideRequestPoint(request, hubs.getReferenceById(point.hubId()),
                        point.walkSeconds(), point.estimated(), anchor.km(), anchor.seconds()));
            }
            return requests.saveAndFlush(request);
        });
    }

    public Preview preview(long memberId, NewRequest input) {
        Instant requestedAt=clock.now();
        Prepared prepared = new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager).execute(status -> prepare(memberId, input));
        Quote quote = quote(memberId, input, prepared, true);
        Instant deadline = input.requestMode()==RequestMode.INSTANT ? requestedAt.plusSeconds(matchingSettings.instantMatchMinutes()*60L)
                : quote.deadline() != null ? quote.deadline() : input.windowEnd().minus(LEAD);
        return new Preview(quote.solo().fare(), (quote.solo().seconds() + 59) / 60, deadline);
    }

    private void requireAvailable(long memberId) {
        safety.requireUnrestricted(memberId);
        if (isTransferRestricted(memberId)) throw ApiException.conflict("TRANSFER_OVERDUE", "24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없습니다.");
        if (payments.overdue(memberId)) throw ApiException.conflict("ADDITIONAL_PAYMENT_OVERDUE", "추가 결제 기한이 지나 새 요청을 할 수 없습니다. 결제를 마친 뒤 다시 시도해 주세요.");
        if (requests.findByActiveMemberId(memberId).isPresent()) throw ApiException.conflict("ACTIVE_REQUEST", "이미 진행 중인 요청이 있습니다.");
    }

    private Prepared prepare(long memberId, NewRequest input) {
        Member member = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        if (!member.hasProfile()) throw ApiException.badRequest("PROFILE_REQUIRED", "닉네임과 성별을 먼저 입력해 주세요.");
        if (input.direction() == null) throw ApiException.badRequest("INVALID_DIRECTION", "방향을 입력해 주세요.");
        if (!Set.of(0, 5, 10, 15).contains(input.earlyMinutes())) throw ApiException.badRequest("INVALID_EARLY_ARRIVAL", "일찍 도착 허용은 0, 5, 10, 15분 중 골라 주세요.");
        validateTimes(input);
        if (input.direction() == Direction.TO_SCHOOL) {
            if (!sequentialPickup) throw ApiException.badRequest("SEQUENTIAL_PICKUP_DISABLED", "등교 순차 승차는 현재 사용할 수 없습니다.");
            return new Prepared(input.point(), new GeoPoint(member.getSchool().getLat(), member.getSchool().getLon()), List.of());
        }
        List<Long> ids = input.pointIds() == null ? (input.hubId() == null ? List.of() : List.of(input.hubId())) : input.pointIds();
        if (ids.isEmpty() || ids.size() > 3 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw ApiException.badRequest("INVALID_POINTS", "승차 지점은 중복 없이 1~3곳을 골라 주세요.");
        if (ids.size() > 1 && input.origin() == null) throw ApiException.badRequest("ORIGIN_REQUIRED", "승차 지점을 여러 곳 고르면 출발지를 입력해 주세요.");
        List<Hub> selected = ids.stream().map(id -> hubs.findById(id)
                .filter(h -> h.isActive() && h.isFieldChecked() && h.getSchools().contains(member.getSchool()))
                .orElseThrow(() -> ApiException.badRequest("HUB_NOT_FOUND", "사용할 수 없는 승차 지점입니다."))).toList();
        GeoPoint origin = input.origin() == null ? new GeoPoint(selected.getFirst().getLat(), selected.getFirst().getLon()) : input.origin().point();
        if (!Double.isFinite(origin.lat()) || !Double.isFinite(origin.lon()) || Math.abs(origin.lat()) > 90 || Math.abs(origin.lon()) > 180) throw ApiException.badRequest("INVALID_ORIGIN", "출발지 좌표가 올바르지 않습니다.");
        List<PointInput> points = selected.stream().map(h -> {
            GeoPoint point = new GeoPoint(h.getLat(), h.getLon());
            WalkEstimator.Walk walk = input.origin() == null ? new WalkEstimator.Walk(0, true) : walks.estimate(origin, point);
            if(input.requestMode()==RequestMode.INSTANT && walk.seconds()>instantSettings.getInstantMaxWalkSeconds()) throw ApiException.badRequest("INSTANT_WALK_TOO_LONG", "즉시는 걸어서 "+instantSettings.getInstantMaxWalkSeconds()/60+"분 안 승차 지점만 고를 수 있어요.");
            if (walk.seconds() > 600) throw ApiException.badRequest("WALK_TOO_LONG", "걷는 시간이 10분을 넘는 승차 지점입니다.");
            return new PointInput(h.getId(), point, walk.seconds(), walk.estimated());
        }).toList();
        return new Prepared(origin, input.point(), points);
    }

    private Quote quote(long memberId, NewRequest input, Prepared prepared, boolean preview) {
        try {
            TripPath path = new TripPath(prepared.origin(), List.of(prepared.destination()));
            boolean instant = input.requestMode() == RequestMode.INSTANT;
            Instant at = instant ? null : input.basis() == TimeBasis.ARRIVE ? input.arriveBy().minusSeconds(3600) : input.windowStart();
            RouteClient.Route solo = preview ? soloRoutes.preview(memberId, path, at) : soloRoutes.route(path, at);
            if (!instant && input.basis() == TimeBasis.ARRIVE) {
                at = input.arriveBy().minusSeconds(solo.seconds());
                solo = preview ? soloRoutes.preview(memberId, path, at) : soloRoutes.route(path, at);
            }
            requireTimely(input, solo);
            List<RouteClient.Route> anchors = new ArrayList<>();
            for (PointInput point : prepared.points()) {
                TripPath anchorPath = new TripPath(point.point(), List.of(prepared.destination()));
                anchors.add(point.point().equals(prepared.origin()) ? solo : preview ? soloRoutes.preview(memberId, anchorPath, at) : soloRoutes.route(anchorPath, at));
            }
            return new Quote(solo, anchors, input.basis() == TimeBasis.ARRIVE ? arrivalDeadline(input, solo) : null);
        } catch (RouteClient.RouteUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_UNAVAILABLE", "경로를 조회하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private Instant arrivalDeadline(NewRequest input, RouteClient.Route solo) {
        // 사람별 모집 마감은 단독 예상 출발에서 P와 추가 여유 35분을 뺀다.
        Instant deadline = input.arriveBy().minusSeconds(solo.seconds()).minus(LEAD).minusSeconds(35 * 60);
        return input.direction() == Direction.TO_SCHOOL ? AppClock.slotOf(deadline) : AppClock.minuteOf(deadline);
    }

    private void requireTimely(NewRequest input, RouteClient.Route solo) {
        if (input.basis() == TimeBasis.ARRIVE && arrivalDeadline(input, solo).isBefore(AppClock.nextMinuteAfter(clock.now()))) throw ApiException.badRequest("TOO_LATE", "도착 희망 시각이 너무 가까워 일행을 찾을 시간이 없습니다.");
    }

    private void validateTimes(NewRequest input) {
        if(input.requestMode()==RequestMode.INSTANT) {
            if(input.basis()!=TimeBasis.DEPART) throw ApiException.badRequest("INSTANT_DEPART_ONLY", "즉시 요청은 출발 기준만 사용할 수 있어요.");
            return; // 옛 즉시 입력에 window가 있어도 사용하지 않는다.
        }
        Instant reference;
        if (input.basis() == TimeBasis.ARRIVE) {
            reference = input.arriveBy();
            if (reference == null || !isFiveMinuteUnit(reference)) throw ApiException.badRequest("INVALID_ARRIVAL", "도착 희망 시각은 5분 단위로만 고를 수 있습니다.");
            if (!clock.now().isBefore(reference)) throw ApiException.badRequest("TOO_LATE", "이미 지난 시각입니다.");
        } else {
            Instant start = input.windowStart(), end = input.windowEnd();
            if (start == null || end == null || !isFiveMinuteUnit(start) || !isFiveMinuteUnit(end) || end.isBefore(start) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) throw ApiException.badRequest("INVALID_WINDOW", "출발 가능 시간은 5분 단위, 최대 60분으로 입력해 주세요.");
            if (!clock.now().isBefore(end.minus(LEAD))) throw ApiException.badRequest("TOO_LATE", "모집 마감이 지난 요청입니다.");
            reference = start;
        }
        Instant limit = clock.now().atZone(SEOUL).toLocalDate().plusDays(2).atStartOfDay(SEOUL).toInstant();
        if (input.requestMode() == RequestMode.RESERVATION && !reference.isBefore(limit)) throw ApiException.badRequest("RESERVATION_TOO_FAR", "예약은 다음 날 23시 59분까지만 받습니다.");
    }

    /** 대기 중이면 요청만 취소하고, 매칭됐으면 그 사람만 일행에서 뺀다. */
    public void cancel(long memberId, long requestId) { if(!joins.cancelPending(memberId,requestId))cancel(memberId,requestId,false,null); }

    public boolean leaveAfterChange(long memberId, long partyId, long version) {
        Long requestId=new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager).execute(status -> {
            var party=partyRepository.findByIdForUpdate(partyId).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
            if(!party.paymentFlow() || party.getGroupVersion()<=1 || party.getGroupVersion()!=version) throw ApiException.conflict("PARTY_CHANGED", "변경된 일행을 다시 확인해 주세요.");
            return requests.findByPartyOrderByRideOrder(party).stream().filter(r->r.getMember().getId()==memberId)
                    .findFirst().orElseThrow(()->ApiException.forbidden("현재 일행원만 빠질 수 있어요.")).getId();
        });
        return cancel(memberId,requestId,true,version);
    }

    private boolean cancel(long memberId,long requestId,boolean changed,Long version) {
        record Plan(Long partyId,boolean free,long groupVersion,boolean paymentFlow) { }
        Plan plan=new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager).execute(status -> {
            RideRequest reference=requests.findById(requestId).filter(r->r.getMember().getId()==memberId)
                    .orElseThrow(()->ApiException.notFound("없는 요청입니다."));
            if(reference.getStatus()==RequestStatus.WAITING && !changed) { reference.finish(RequestStatus.CANCELED); return null; }
            if(reference.getParty()==null) throw ApiException.conflict("CANNOT_CANCEL", "취소할 수 없는 상태입니다.");
            var party=partyRepository.findByIdForUpdate(reference.getParty().getId()).orElseThrow();
            if(version!=null && (party.getGroupVersion()!=version || party.getGroupVersion()<=1)) throw ApiException.conflict("PARTY_CHANGED", "일행이 바뀌었어요. 다시 확인해 주세요.");
            if(!reference.canCancelAt(clock.now())) throw ApiException.conflict("DEPARTED", "출발 시각이 지나 취소할 수 없습니다.");
            boolean free=party.paymentFlow() && (changed?clock.now().isBefore(party.getDepartureAt().minusSeconds(600)):!clock.now().isAfter(party.freeCancelUntil()));
            return new Plan(party.getId(),free,party.getGroupVersion(),party.paymentFlow());
        });
        if(plan==null) return true;
        Set<Long> held=plan.free()||!plan.paymentFlow()?Set.of():Set.of(requestId);
        shrinker.remove(plan.partyId(),Set.of(requestId),clock.now(),false,
                plan.free()?(changed?"CHANGE_EXIT_FREE":"FREE_CANCEL"):"LATE_CANCEL",plan.groupVersion(),held);
        boolean free=plan.free() && !partyTerms.findByPartyIdAndRequestId(plan.partyId(),requestId).map(t->t.isRefundHeld() || "LATE_CANCEL".equals(t.getExitReason())).orElse(false);
        if(free) payments.refundMember(plan.partyId(),memberId,"무료 취소");
        return free;
    }

    @Transactional(readOnly = true)
    public boolean isRestricted(long memberId) {
        return safety.activeRestriction(memberId) != null || isTransferRestricted(memberId) || payments.overdue(memberId);
    }

    @Transactional(readOnly = true)
    public boolean isTransferRestricted(long memberId) {
        return transfers.existsOverdue(memberId, TransferStatus.REQUESTED, clock.now().minus(OVERDUE));
    }

    private static boolean isFiveMinuteUnit(Instant time) {
        return time.equals(time.truncatedTo(ChronoUnit.MINUTES)) && time.getEpochSecond() % 300 == 0;
    }
}
