package com.daniellaera.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One INFO line per AI call: user, model, input/output tokens, cache hit. (No actuator on the classpath, so log only.) */
@Component
@Slf4j
public class AiUsageLogger {

    private final String configuredModel;

    public AiUsageLogger(@Value("${spring.ai.anthropic.chat.options.model:unknown}") String configuredModel) {
        this.configuredModel = configuredModel;
    }

    public void logCacheHit(String user) {
        log.info("AI usage user={} model={} in=0 out=0 cached=Y", user, configuredModel);
    }

    public void logCall(String user, String model, Usage usage) {
        int in = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int out = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        String m = model != null && !model.isBlank() ? model : configuredModel;
        log.info("AI usage user={} model={} in={} out={} cached=N", user, m, in, out);
    }
}
