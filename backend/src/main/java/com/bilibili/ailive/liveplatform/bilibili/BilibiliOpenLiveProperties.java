package com.bilibili.ailive.liveplatform.bilibili;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.live-platform.bilibili")
public record BilibiliOpenLiveProperties(
        boolean openLiveEnabled,
        boolean autoConnect,
        URI apiBaseUrl,
        String accessKeyId,
        String accessKeySecret,
        long appId,
        String identityCode,
        Duration requestTimeout,
        Duration heartbeatInterval,
        Duration reconnectDelay,
        int maxSessionRestartAttempts,
        int maxHeartbeatFailures,
        int eventWorkerThreads,
        int maxPendingEvents
) {

    public BilibiliOpenLiveProperties {
        if (apiBaseUrl == null) {
            throw new IllegalArgumentException("Bilibili API base URL is required");
        }
        if (!isPositive(requestTimeout) || !isPositive(heartbeatInterval) || !isPositive(reconnectDelay)) {
            throw new IllegalArgumentException("Bilibili Open Live durations must be positive");
        }
        if (maxSessionRestartAttempts < 1 || maxHeartbeatFailures < 1 || eventWorkerThreads < 1 || maxPendingEvents < 1) {
            throw new IllegalArgumentException("Bilibili event executor limits must be positive");
        }
        accessKeyId = normalize(accessKeyId);
        accessKeySecret = normalize(accessKeySecret);
        identityCode = normalize(identityCode);
    }

    public boolean isConfigured() {
        return openLiveEnabled
                && accessKeyId != null
                && accessKeySecret != null
                && identityCode != null
                && appId > 0;
    }

    public void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Bilibili Open Live requires access key, access secret, app ID and broadcaster identity code"
            );
        }
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
