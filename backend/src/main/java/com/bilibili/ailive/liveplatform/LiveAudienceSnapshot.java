package com.bilibili.ailive.liveplatform;

public record LiveAudienceSnapshot(
        long recentlyActiveViewers,
        long observedSessionViewers,
        boolean estimated
) {

    public static LiveAudienceSnapshot unavailable() {
        return new LiveAudienceSnapshot(0, 0, true);
    }
}
