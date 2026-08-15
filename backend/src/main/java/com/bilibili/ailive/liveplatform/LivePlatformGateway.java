package com.bilibili.ailive.liveplatform;

public interface LivePlatformGateway {

    LivePlatform platform();

    boolean canSendDanmaku();

    DanmakuSendResult sendDanmaku(DanmakuSendRequest request);
}
