package com.daniellaera.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AiCacheServiceTest {

    /** Mutable clock for TTL tests. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void normalisesKeys_caseWhitespaceAndTrim() {
        AiCacheService cache = new AiCacheService();
        cache.put("  What   Books do you HAVE? ", "ten books");

        assertThat(cache.get("what books do you have?")).isEqualTo("ten books");
        assertThat(cache.get("WHAT BOOKS\tDO YOU HAVE?  ")).isEqualTo("ten books");
        assertThat(cache.get("something else")).isNull();
    }

    @Test
    void neverCachesEmptyOrNullAnswers() {
        AiCacheService cache = new AiCacheService();
        cache.put("q", "");
        cache.put("q2", "   ");
        cache.put("q3", null);

        assertThat(cache.get("q")).isNull();
        assertThat(cache.get("q2")).isNull();
        assertThat(cache.get("q3")).isNull();
    }

    @Test
    void entriesExpireAfterTtl() {
        TestClock clock = new TestClock();
        AiCacheService cache = new AiCacheService(clock, Duration.ofHours(24), 10);
        cache.put("q", "a");

        clock.advance(Duration.ofHours(23));
        assertThat(cache.get("q")).isEqualTo("a");

        clock.advance(Duration.ofHours(1)); // exactly at expiry
        assertThat(cache.get("q")).isNull();
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        AiCacheService cache = new AiCacheService(Clock.systemUTC(), Duration.ofHours(1), 2);
        cache.put("a", "1");
        cache.put("b", "2");
        assertThat(cache.get("a")).isEqualTo("1"); // touch a -> b is now eldest
        cache.put("c", "3");

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    @Test
    void clearRemovesEverything() {
        AiCacheService cache = new AiCacheService();
        cache.put("a", "1");
        cache.clear();
        assertThat(cache.get("a")).isNull();
    }
}
