package com.daniellaera.backend.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private boolean enabled = true;
    private int dailyLimit = 20;
    private int anonymousDailyLimit = 5;
    private int searchLimit;
    /** Max tool calls the model may make per chat question (guards cost and loops). */
    private int maxToolCalls = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public int getAnonymousDailyLimit() {
        return anonymousDailyLimit;
    }

    public void setAnonymousDailyLimit(int anonymousDailyLimit) {
        this.anonymousDailyLimit = anonymousDailyLimit;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }
}
