package com.daniellaera.backend.service;

import com.daniellaera.backend.properties.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AiRateLimiterServiceTest {

    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private TestClock clock;
    private AiRateLimiterService limiter;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties();
        props.setDailyLimit(3);
        props.setAnonymousDailyLimit(2);
        clock = new TestClock();
        limiter = new AiRateLimiterService(props, clock);
    }

    @Test
    void allowsUpToDailyLimitThenBlocks() {
        assertThat(limiter.getRemainingRequests("alice")).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.isAllowed("alice")).isTrue();
            limiter.recordRequest("alice");
        }
        assertThat(limiter.isAllowed("alice")).isFalse();
        assertThat(limiter.getRemainingRequests("alice")).isZero();
        assertThat(limiter.isAllowed("bob")).isTrue(); // per user
    }

    @Test
    void resetsWhenTheDayChanges() {
        for (int i = 0; i < 3; i++) limiter.recordRequest("alice");
        assertThat(limiter.isAllowed("alice")).isFalse();

        clock.advance(Duration.ofHours(5)); // still same day (15:00)
        assertThat(limiter.isAllowed("alice")).isFalse();

        clock.advance(Duration.ofHours(10)); // next day
        assertThat(limiter.isAllowed("alice")).isTrue();
        assertThat(limiter.getRemainingRequests("alice")).isEqualTo(3);
    }

    @Test
    void anonymousClientsUseSeparateLowerLimitPerIp() {
        limiter.recordAnonymousRequest("1.2.3.4");
        assertThat(limiter.isAnonymousAllowed("1.2.3.4")).isTrue();
        limiter.recordAnonymousRequest("1.2.3.4");
        assertThat(limiter.isAnonymousAllowed("1.2.3.4")).isFalse();
        assertThat(limiter.isAnonymousAllowed("5.6.7.8")).isTrue();
    }

    @Test
    void ipKeysCannotCollideWithUsernames() {
        limiter.recordAnonymousRequest("alice");
        limiter.recordAnonymousRequest("alice");
        assertThat(limiter.isAnonymousAllowed("alice")).isFalse();
        assertThat(limiter.isAllowed("alice")).isTrue();
    }
}
