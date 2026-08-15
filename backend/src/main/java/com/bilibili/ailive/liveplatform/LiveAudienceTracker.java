package com.bilibili.ailive.liveplatform;

public interface LiveAudienceTracker {

    void observe(LiveAudienceActivity activity, String sessionId);

    LiveAudienceSnapshot snapshot(LivePlatform platform, String roomId, String sessionId);
}
