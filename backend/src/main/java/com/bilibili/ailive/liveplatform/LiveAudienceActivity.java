package com.bilibili.ailive.liveplatform;

import java.time.Instant;

public record LiveAudienceActivity(
        LivePlatform platform,
        String roomId,
        String viewerId,
        Instant occurredAt
) {

    public LiveAudienceActivity {
        if (platform == null || isBlank(roomId) || isBlank(viewerId) || occurredAt == null) {
            throw new IllegalArgumentException("Audience activity requires platform, room, viewer and timestamp");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
