package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyJoin;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestMode;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.TimeBasis;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 홈의 "지금 모집 중" 게시판. 같은 성별 풀에서 합류할 수 있는 하교 일행과, 시간대별로 기다리는 사람 수를 보여 준다.
 * 다른 학생의 하차 지점·출발지는 주지 않고, 일행은 별명과 공개한 학과·학번만 보인다.
 */
@Service
@RequiredArgsConstructor
public class BoardService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int BOARD_LIMIT = 6;

    public record Person(String nickname, String profile) { }
    public record PartyCard(long id, Direction direction, String anchorName, String meetingPoint, double anchorLat, double anchorLon, Instant departureAt,
                            Instant joinUntil, int riders, int seats, boolean instant, List<Person> people) { }
    public record WaitingCount(Direction direction, String anchorName, Instant hour, int count) { }
    public record Board(Instant now, List<PartyCard> parties, List<WaitingCount> waiting) { }
    public record JoinInput(String pointName, double pointLat, double pointLon, String memo) { }
    private record Target(long hubId, double lat, double lon, String name, Instant departure) { }

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final MemberSafetyService safety;
    private final PartyJoinService joins;
    private final RideRequestService rideRequests;
    private final ProfileService profiles;
    private final MatchingSettings settings;
    private final AppClock clock;
    private final PlatformTransactionManager manager;

    @Transactional(readOnly = true)
    public Board of(long viewerId) {
        Member viewer = members.findById(viewerId).orElseThrow(ApiException::unauthorized);
        Long viewerSchoolId = viewer.getSchool() == null ? null : viewer.getSchool().getId();
        Instant now = clock.now();
        List<PartyCard> cards = new ArrayList<>();
        for (Party party : parties.findByStatusIn(List.of(PartyStatus.PREPAYING, PartyStatus.READY))) {
            if (!joinable(party, now)) continue;
            if (viewerSchoolId != null && party.getHub().getSchools().stream().noneMatch(s -> s.getId().equals(viewerSchoolId))) continue;
            List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
            if (riders.isEmpty() || riders.size() >= settings.maxGroupSize()) continue;
            if (riders.stream().anyMatch(r -> r.getTimeBasis() == TimeBasis.ARRIVE)) continue;
            if (riders.stream().anyMatch(r -> r.getMember().getId() == viewerId || r.getMember().getGender() != viewer.getGender())) continue;
            List<Long> ids = new ArrayList<>(riders.stream().map(r -> r.getMember().getId()).toList());
            ids.add(viewerId);
            Map<Long, java.util.Set<Long>> avoids = safety.avoidMap(ids);
            if (ids.stream().anyMatch(id -> avoids.getOrDefault(id, java.util.Set.of()).stream().anyMatch(ids::contains))) continue;
            List<Person> people = riders.stream().map(r -> new Person(r.getMember().getNickname(), profiles.publicLine(r.getMember()))).toList();
            Direction cardDirection = riders.stream().allMatch(r -> displayDirection(r, viewer.getSchool()) == Direction.TO_SCHOOL)
                    ? Direction.TO_SCHOOL : party.getDirection();
            cards.add(new PartyCard(party.getId(), cardDirection, party.getHub().getName(), party.getHub().getMeetingPoint(),
                    party.getHub().getLat(), party.getHub().getLon(),
                    party.getDepartureAt(), cutoff(party), riders.size(), settings.maxGroupSize() - riders.size(), party.isInstant(), people));
        }
        cards.sort(Comparator.comparing(PartyCard::departureAt));
        return new Board(now, cards.stream().limit(BOARD_LIMIT).toList(), waiting(viewer));
    }

    /** 게시판의 일행에 합류한다. 그 일행 출발 시각을 포함하는 요청을 만들고 바로 합류를 신청한다. 실패하면 대기 중인 요청만 거둔다. */
    public PartyJoin join(long viewerId, long partyId, JoinInput input) {
        // 일행과 거점 값은 짧은 읽기 트랜잭션에서 꺼낸다. 요청 만들기와 합류 신청은 각자 트랜잭션을 연다.
        Target target = new TransactionTemplate(manager).execute(status -> {
            Member viewer = members.findById(viewerId).orElseThrow(ApiException::unauthorized);
            Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
            if (!joinable(party, clock.now())) throw ApiException.conflict("JOIN_CLOSED", "합류 마감이 지났거나 합류할 수 없는 일행이에요.");
            if (viewer.getSchool() != null && party.getHub().getSchools().stream().noneMatch(s -> s.getId().equals(viewer.getSchool().getId()))) {
                throw ApiException.conflict("JOIN_POOL", "같은 학교 승차 지점 일행만 합류할 수 있어요.");
            }
            if (requests.findByPartyOrderByRideOrder(party).stream().anyMatch(r -> r.getTimeBasis() == TimeBasis.ARRIVE)) {
                throw ApiException.conflict("JOIN_POOL", "도착 기준 일행은 게시판에서 합류할 수 없어요.");
            }
            var hub = party.getHub();
            return new Target(hub.getId(), hub.getLat(), hub.getLon(), hub.getName(), party.getDepartureAt());
        });
        Instant start = target.departure().truncatedTo(ChronoUnit.MINUTES);
        start = start.minus(Duration.ofMinutes(start.atZone(SEOUL).getMinute() % 5));
        RideRequest created = rideRequests.create(viewerId, new RideRequestService.NewRequest(Direction.FROM_HUB, target.hubId(),
                start, start.plus(Duration.ofMinutes(30)), null, input.pointName(), input.pointLat(), input.pointLon(), input.memo(),
                RequestMode.RESERVATION, TimeBasis.DEPART, new RideRequestService.Origin(target.lat(), target.lon(), target.name()),
                List.of(target.hubId()), null));
        try {
            return joins.offer(viewerId, partyId, created.getId());
        } catch (RuntimeException failure) {
            // 외부 경로 조회 도중 슬롯 매칭이 먼저 성사된 경우에는 타 일행을 건드리지 않고, 아직 WAITING인 요청만 거둔다.
            new TransactionTemplate(manager).executeWithoutResult(status -> requests.findById(created.getId())
                    .filter(r -> r.getStatus() == RequestStatus.WAITING && r.getParty() == null)
                    .ifPresent(r -> r.finish(RequestStatus.CANCELED)));
            throw failure;
        }
    }

    private boolean joinable(Party party, Instant now) {
        return party.canAcceptJoin() && party.getDirection() == Direction.FROM_HUB && party.getHub() != null
                && party.getHub().isActive() && party.getHub().isFieldChecked()
                && party.getPendingJoinId() == null && now.isBefore(cutoff(party));
    }

    private Instant cutoff(Party party) {
        return party.isInstant() ? party.getConfirmedAt().plusSeconds(settings.instantPrepMinutes() * 60L)
                : party.getDepartureAt().minusSeconds(settings.leadMinutes() * 60L);
    }

    /**
     * 공통 승차(FROM_HUB)로 만든 등교 요청을 게시판·대기 집계·이용 내역에서 등교로 구분한다. 이 규칙은 서버 한 곳에만 둔다
     * (화면은 응답의 displayDirection을 그대로 쓴다). 택시가 출발하는 승차 지점보다 목적지가 학교에 가깝고,
     * 목적지가 학교에서 1km 안이면 등교다. 승차 지점이 없으면 요청자의 출발지로 본다.
     */
    public static Direction displayDirection(RideRequest r) {
        return displayDirection(r, r.getMember() != null ? r.getMember().getSchool() : null);
    }

    /**
     * 요청자의 학교를 따로 받는 판정. 응답을 만드는 곳(트랜잭션 밖)에서는 요청의 회원을 지연 로딩할 수 없으므로,
     * 학교를 함께 읽어 둔 로그인 회원의 학교를 넘긴다. 학교를 모르면 원래 방향을 쓴다.
     */
    public static Direction displayDirection(RideRequest r, com.campustaximate.server.domain.School school) {
        if (r.getDirection() == Direction.TO_SCHOOL) return Direction.TO_SCHOOL;
        if (school == null) return r.getDirection();
        var campus = new com.campustaximate.matching.GeoPoint(school.getLat(), school.getLon());
        var start = r.getHub() != null ? new com.campustaximate.matching.GeoPoint(r.getHub().getLat(), r.getHub().getLon()) : r.origin();
        double destinationDistance = r.point().distanceKm(campus);
        return destinationDistance <= 1.0 && start.distanceKm(campus) > destinationDistance
                ? Direction.TO_SCHOOL : Direction.FROM_HUB;
    }

    /** 같은 학교·성별·방향으로 기다리는 사람 수를 거점과 시간(1시간 단위)별로 센다. 본인은 뺀다. */
    private List<WaitingCount> waiting(Member viewer) {
        Long viewerSchoolId = viewer.getSchool() == null ? null : viewer.getSchool().getId();
        Map<String, WaitingCount> counts = new TreeMap<>();
        for (Direction direction : Direction.values()) {
            for (RideRequest r : requests.findByStatusAndDirection(RequestStatus.WAITING, direction)) {
                if (r.getMember().getId() == viewer.getId() || r.getMember().getGender() != viewer.getGender()) continue;
                if (viewerSchoolId != null && (r.getMember().getSchool() == null || !viewerSchoolId.equals(r.getMember().getSchool().getId()))) continue;
                Direction display = displayDirection(r, viewer.getSchool());
                Instant at = r.getWindowStart() != null ? r.getWindowStart() : r.getArriveBy();
                if (at == null) at = clock.now();
                Instant hour = at.atZone(SEOUL).truncatedTo(ChronoUnit.HOURS).toInstant();
                String anchor = r.getHub() != null ? r.getHub().getName() : r.getSchool() == null ? "" : r.getSchool().getGateName();
                String key = hour + "|" + display + "|" + anchor;
                WaitingCount old = counts.get(key);
                counts.put(key, new WaitingCount(display, anchor, hour, old == null ? 1 : old.count() + 1));
            }
        }
        return counts.values().stream().filter(Objects::nonNull).limit(BOARD_LIMIT).toList();
    }
}
