package com.bilibili.ailive.liveplatform;

public record DanmakuSendRequest(
        LivePlatform platform,
        String roomId,
        String text
) {
}
