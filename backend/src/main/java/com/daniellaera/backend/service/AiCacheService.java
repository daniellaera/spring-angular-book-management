package com.daniellaera.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded in-memory LRU cache with TTL for AI answers.
 * Keys are normalised (trimmed, lower-cased, whitespace collapsed) so trivial
 * variations of the same question hit the cache instead of costing tokens.
 */
@Service
@Slf4j
public class AiCacheService {

    private static final int MAX_ENTRIES = 500;
    private static final Duration TTL = Duration.ofHours(24);

    private record CachedAnswer(String answer, long expiresAtMillis) {}

    private final Map<String, CachedAnswer> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedAnswer> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static String key(String question) {
        return question == null ? "" : question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public synchronized String get(String question) {
        String k = key(question);
        CachedAnswer e = cache.get(k);
        if (e == null) return null;
        if (e.expiresAtMillis() < System.currentTimeMillis()) {
            cache.remove(k);
            return null;
        }
        return e.answer();
    }

    public synchronized void put(String question, String answer) {
        if (answer == null || answer.isBlank()) return; // never cache empty/failed answers
        cache.put(key(question), new CachedAnswer(answer, System.currentTimeMillis() + TTL.toMillis()));
    }

    public synchronized void clear() {
        cache.clear();
        log.info("AI cache cleared");
    }
}
