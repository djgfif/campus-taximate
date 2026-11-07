package com.campustaximate.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

    @Test
    void blocksOverLimitAndResetsNextMinute() {
        RateLimitFilter filter = new RateLimitFilter(new ObjectMapper());
        assertThat(filter.allow("auth:1", 2, 100)).isTrue();
        assertThat(filter.allow("auth:1", 2, 100)).isTrue();
        assertThat(filter.allow("auth:1", 2, 100)).isFalse();
        assertThat(filter.allow("auth:2", 2, 100)).isTrue();
        assertThat(filter.allow("auth:1", 2, 101)).isTrue();
    }
}
