package com.bilibili.ailive.liveplatform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.live-output.danmaku")
public record DanmakuProperties(
        int maxCharacters,
        Duration minimumInterval,
        Duration echoRetention
) {

    public DanmakuProperties {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("Danmaku maximum characters must be positive");
        }
        if (!isPositive(minimumInterval) || !isPositive(echoRetention)) {
            throw new IllegalArgumentException("Danmaku durations must be positive");
        }
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
