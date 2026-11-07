package com.campustaximate.matching;

import java.util.ArrayList;
import java.util.List;

/** 경로 조회에 넘길 출발지와 차례로 거칠 지점. 마지막 지점이 목적지다. */
public record TripPath(GeoPoint origin, List<GeoPoint> stops) {

    public TripPath {
        stops = List.copyOf(stops);
    }

    /** 출발지까지 포함해 이어지는 같은 정차 지점을 하나로 줄인다. */
    public TripPath merged() {
        List<GeoPoint> all = new ArrayList<>();
        all.add(origin);
        all.addAll(stops);
        List<GeoPoint> merged = GeoPoint.mergeStops(all);
        return new TripPath(merged.get(0), merged.subList(1, merged.size()));
    }
}
