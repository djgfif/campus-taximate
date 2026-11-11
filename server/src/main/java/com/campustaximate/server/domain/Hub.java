package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Hub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** 일행이 만나는 정확한 위치 문구. */
    @Column(nullable = false)
    private String meetingPoint;

    private boolean fieldChecked = true;

    private boolean active = true;

    private double lat;

    private double lon;

    @ManyToMany
    @JoinTable(name = "school_hub",
            joinColumns = @JoinColumn(name = "hub_id"),
            inverseJoinColumns = @JoinColumn(name = "school_id"))
    private Set<School> schools = new HashSet<>();

    public Hub(String name, String meetingPoint, double lat, double lon, Set<School> schools) {
        this.name = name;
        this.meetingPoint = meetingPoint;
        this.lat = lat;
        this.lon = lon;
        this.schools = schools;
    }
}
