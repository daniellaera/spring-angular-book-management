package com.daniellaera.backend.service;

import com.daniellaera.backend.properties.AiProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-user daily quota. Counters reset automatically when the day changes. */
@Service
public class AiRateLimiterService {

    private final AiProperties aiProperties;
    private final Map<String, Integer> userRequests = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay = LocalDate.now();

    public AiRateLimiterService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            synchronized (this) {
                if (!today.equals(currentDay)) {
                    userRequests.clear();
                    currentDay = today;
                }
            }
        }
    }

    public boolean isAllowed(String username) {
        return getRemainingRequests(username) > 0;
    }

    public void recordRequest(String username) {
        resetIfNewDay();
        userRequests.merge(username, 1, Integer::sum);
    }

    public int getRemainingRequests(String username) {
        resetIfNewDay();
        return Math.max(0, aiProperties.getDailyLimit() - userRequests.getOrDefault(username, 0));
    }
}
