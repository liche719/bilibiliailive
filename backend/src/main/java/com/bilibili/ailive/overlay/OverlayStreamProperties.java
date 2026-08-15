package com.bilibili.ailive.overlay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.sse.overlay-stream")
public record OverlayStreamProperties(
        Duration characterInterval,
        int maxPendingReplies
) {

    public OverlayStreamProperties {
        if (characterInterval == null
                || characterInterval.isNegative()
                || characterInterval.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("Overlay stream character interval must be between zero and one second");
        }
        if (maxPendingReplies < 1) {
            throw new IllegalArgumentException("Overlay stream queue capacity must be positive");
        }
    }
}
