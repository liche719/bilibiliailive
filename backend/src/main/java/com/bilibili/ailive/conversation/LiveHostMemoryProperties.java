package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.memory")
public record LiveHostMemoryProperties(
        int maxMessages,
        int maxActiveMemories,
        Duration ttl,
        String redisKeyPrefix
) {

    public LiveHostMemoryProperties {
        if (maxMessages < 1 || maxActiveMemories < 1) {
            throw new IllegalArgumentException("Live host memory limits must be positive");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Live host memory TTL must be positive");
        }
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Live host memory Redis key prefix must not be blank");
        }
        redisKeyPrefix = redisKeyPrefix.trim();
    }
}
