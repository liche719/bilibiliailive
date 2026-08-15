package com.bilibili.ailive.liveplatform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.live-platform.audience")
public record LiveAudienceProperties(
        Duration activeWindow,
        Duration sessionTtl,
        String redisKeyPrefix
) {

    public LiveAudienceProperties {
        if (activeWindow == null || activeWindow.isNegative() || activeWindow.isZero()) {
            throw new IllegalArgumentException("Audience active window must be positive");
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("Audience session TTL must be positive");
        }
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Audience Redis key prefix must not be blank");
        }
        redisKeyPrefix = redisKeyPrefix.trim();
    }
}
