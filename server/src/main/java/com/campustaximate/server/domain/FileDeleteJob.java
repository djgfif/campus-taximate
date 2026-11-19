package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A detached upload remains discoverable until storage confirms it is gone. */
@Entity
@Getter
@NoArgsConstructor
public class FileDeleteJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 1024)
    private String path;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    @Column(nullable = false)
    private int attempts;

    public FileDeleteJob(String path, Instant nextAttemptAt) {
        this.path = path;
        this.nextAttemptAt = nextAttemptAt;
    }
}
