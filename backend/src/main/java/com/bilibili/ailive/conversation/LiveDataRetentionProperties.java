package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.data-retention")
public record LiveDataRetentionProperties(Duration replyHistory, Duration cleanupInterval) {

    public LiveDataRetentionProperties {
        if (!isPositive(replyHistory) || !isPositive(cleanupInterval)) {
            throw new IllegalArgumentException("Live data retention durations must be positive");
        }
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
