package com.campustaximate.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoDatabaseResetTest {

    @Test
    void wipesOnlyThisComputersDatabase() {
        assertThat(DemoDatabaseReset.local("jdbc:mysql://127.0.0.1:3307/taximate?useSSL=false")).isTrue();
        assertThat(DemoDatabaseReset.local("jdbc:mysql://localhost:3306/taximate")).isTrue();
        assertThat(DemoDatabaseReset.local("jdbc:h2:mem:taximate;MODE=MySQL")).isTrue();
        assertThat(DemoDatabaseReset.local("jdbc:mysql://db.example.ac.kr:3306/taximate")).isFalse();
        assertThat(DemoDatabaseReset.local("jdbc:mysql://mysql:3306/taximate")).isFalse();
    }
}
