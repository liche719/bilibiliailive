package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.live-platform.welcome")
public record ViewerWelcomeProperties(
        boolean enabled,
        Duration aggregationWindow,
        Duration flushInterval,
        Duration maxDelay,
        Duration deduplicationTtl,
        Duration displayDuration,
        int maxNamedViewers,
        int maxPendingViewers,
        String redisKeyPrefix
) {

    public ViewerWelcomeProperties {
        if (aggregationWindow == null || aggregationWindow.isNegative() || aggregationWindow.isZero()
                || flushInterval == null || flushInterval.isNegative() || flushInterval.isZero()
                || maxDelay == null || maxDelay.compareTo(aggregationWindow) < 0
                || deduplicationTtl == null || deduplicationTtl.isNegative() || deduplicationTtl.isZero()
                || displayDuration == null || displayDuration.isNegative() || displayDuration.isZero()
                || maxNamedViewers < 1 || maxPendingViewers < maxNamedViewers
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Viewer welcome settings must be positive and internally consistent");
        }
    }
}
