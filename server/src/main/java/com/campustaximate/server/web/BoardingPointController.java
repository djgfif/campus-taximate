package com.campustaximate.server.web;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.route.WalkEstimator;
import jakarta.servlet.http.HttpSession;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 출발지 주변의 이용 가능한 공용 승차 지점만 읽는다. 외부 경로 호출은 없다. */
@RestController
@RequiredArgsConstructor
public class BoardingPointController {
    private final MemberRepository members;
    private final HubRepository hubs;
    private final WalkEstimator walks;
    public record Point(long id, String name, String meetingPoint, double lat, double lon,
                        int walkSeconds, boolean walkEstimated) { }
    private record Nearby(Point point, double distance) { }

    @GetMapping("/api/hubs/nearby")
    @Transactional(readOnly = true)
    public List<Point> nearby(@RequestParam double lat, @RequestParam double lon, HttpSession session) {
        Object actor = session.getAttribute(LoginTokenFilter.SESSION_KEY);
        if (!(actor instanceof Long)) throw ApiException.unauthorized();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw ApiException.badRequest("INVALID_ORIGIN", "출발지 좌표를 확인해 주세요.");
        }
        var member = members.findWithSchoolById((Long) actor).orElseThrow(ApiException::unauthorized);
        GeoPoint origin = new GeoPoint(lat, lon);
        return hubs.findBySchool(member.getSchool().getId()).stream().map(hub -> {
            GeoPoint location = new GeoPoint(hub.getLat(), hub.getLon());
            WalkEstimator.Walk walk = walks.estimate(origin, location);
            return new Nearby(new Point(hub.getId(), hub.getName(), hub.getMeetingPoint(), hub.getLat(), hub.getLon(),
                    walk.seconds(), walk.estimated()), origin.distanceKm(location));
        }).filter(p -> p.point().walkSeconds() <= 600)
                .sorted(Comparator.comparingInt((Nearby p) -> p.point().walkSeconds())
                        .thenComparingDouble(Nearby::distance).thenComparingLong(p -> p.point().id()))
                .limit(3).map(Nearby::point).toList();
    }
}
