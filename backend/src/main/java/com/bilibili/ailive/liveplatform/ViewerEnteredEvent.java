package com.bilibili.ailive.liveplatform;

import java.time.Instant;

public record ViewerEnteredEvent(
        LivePlatform platform,
        String roomId,
        String viewerId,
        String viewerName,
        Instant occurredAt
) {

    public ViewerEnteredEvent {
        if (platform == null || isBlank(roomId) || isBlank(viewerId) || occurredAt == null) {
            throw new IllegalArgumentException("A viewer entry requires platform, room, viewer and timestamp");
        }
        viewerName = isBlank(viewerName) ? null : viewerName.trim();
    }

    public String roomExecutionKey() {
        return platform.name() + ":" + roomId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
