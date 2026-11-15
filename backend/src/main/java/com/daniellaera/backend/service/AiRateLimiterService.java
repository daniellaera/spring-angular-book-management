package com.daniellaera.backend.service;

import com.daniellaera.backend.properties.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Daily quota per key (username or client IP). Counters reset automatically when the day changes. */
@Service
public class AiRateLimiterService {

    private final AiProperties aiProperties;
    private final Clock clock;
    private final Map<String, Integer> requests = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay;

    @Autowired
    public AiRateLimiterService(AiProperties aiProperties) {
        this(aiProperties, Clock.systemDefaultZone());
    }

    AiRateLimiterService(AiProperties aiProperties, Clock clock) {
        this.aiProperties = aiProperties;
        this.clock = clock;
        this.currentDay = LocalDate.now(clock);
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDay)) {
            synchronized (this) {
                if (!today.equals(currentDay)) {
                    requests.clear();
                    currentDay = today;
                }
            }
        }
    }

    /** Authenticated users: {@code ai.daily-limit}. */
    public boolean isAllowed(String username) {
        return isAllowed(username, aiProperties.getDailyLimit());
    }

    public boolean isAllowed(String key, int limit) {
        return remaining(key, limit) > 0;
    }

    /** Anonymous clients (keyed by IP): {@code ai.anonymous-daily-limit}. */
    public boolean isAnonymousAllowed(String clientIp) {
        return isAllowed(anonymousKey(clientIp), aiProperties.getAnonymousDailyLimit());
    }

    public void recordAnonymousRequest(String clientIp) {
        recordRequest(anonymousKey(clientIp));
    }

    public void recordRequest(String key) {
        resetIfNewDay();
        requests.merge(key, 1, Integer::sum);
    }

    public int getRemainingRequests(String username) {
        return remaining(username, aiProperties.getDailyLimit());
    }

    private int remaining(String key, int limit) {
        resetIfNewDay();
        return Math.max(0, limit - requests.getOrDefault(key, 0));
    }

    // prefix keeps IP keys from colliding with usernames
    private static String anonymousKey(String clientIp) {
        return "ip:" + clientIp;
    }
}
