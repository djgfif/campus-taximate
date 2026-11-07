package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String emailDomain;

    /** 등교 택시가 내려 주는 곳. */
    @Column(nullable = false)
    private String gateName;

    private double lat;

    private double lon;

    public School(String name, String emailDomain, String gateName, double lat, double lon) {
        this.name = name;
        this.emailDomain = emailDomain;
        this.gateName = gateName;
        this.lat = lat;
        this.lon = lon;
    }
}
