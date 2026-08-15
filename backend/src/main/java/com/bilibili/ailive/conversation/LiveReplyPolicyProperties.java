package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.reply-policy")
public record LiveReplyPolicyProperties(
        int maxReplyCharacters,
        Duration userCooldown,
        int maxModelCallsPerWindow,
        Duration modelCallRateWindow,
        int maxPendingPerRoom,
        int maxConcurrentPerRoom,
        int maxScheduledRooms,
        Duration maxQueueWait,
        int workerThreads,
        String redisKeyPrefix
) {

    public LiveReplyPolicyProperties {
        if (maxReplyCharacters < 1
                || maxModelCallsPerWindow < 1
                || maxPendingPerRoom < 1
                || maxConcurrentPerRoom < 1
                || maxScheduledRooms < 1
                || workerThreads < 1) {
            throw new IllegalArgumentException("Live reply policy limits must be positive");
        }
        if (!isPositive(userCooldown) || !isPositive(modelCallRateWindow) || !isPositive(maxQueueWait)) {
            throw new IllegalArgumentException("Live reply policy durations must be positive");
        }
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Live reply policy Redis key prefix must not be blank");
        }
        redisKeyPrefix = redisKeyPrefix.trim();
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
