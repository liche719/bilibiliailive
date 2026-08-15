package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.room-context")
public record RoomContextProperties(
        int maxEntries,
        Duration ttl,
        String redisKeyPrefix
) {

    public RoomContextProperties {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Room context maximum entries must be positive");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Room context TTL must be positive");
        }
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Room context Redis key prefix must not be blank");
        }
        redisKeyPrefix = redisKeyPrefix.trim();
    }
}
