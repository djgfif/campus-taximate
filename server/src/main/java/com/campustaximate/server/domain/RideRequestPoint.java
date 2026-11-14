package com.campustaximate.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 한 요청이 선택한 승차 지점과 그 지점 기준 경로 값. */
@Entity
@Getter
@NoArgsConstructor
public class RideRequestPoint {
    @EmbeddedId
    private RideRequestPointId id = new RideRequestPointId();

    @MapsId("requestId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private RideRequest request;

    @MapsId("hubId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hub_id")
    private Hub hub;

    private int walkSeconds;
    private boolean walkEstimated;
    private double anchorKm;
    private int anchorSeconds;

    public RideRequestPoint(RideRequest request, Hub hub, int walkSeconds, boolean walkEstimated,
                            double anchorKm, int anchorSeconds) {
        this.request = request;
        this.hub = hub;
        this.id = new RideRequestPointId(request.getId(), hub.getId());
        this.walkSeconds = walkSeconds;
        this.walkEstimated = walkEstimated;
        this.anchorKm = anchorKm;
        this.anchorSeconds = anchorSeconds;
    }
}
