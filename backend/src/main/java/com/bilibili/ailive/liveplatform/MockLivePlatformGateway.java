package com.bilibili.ailive.liveplatform;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MockLivePlatformGateway implements LivePlatformGateway {

    @Override
    public LivePlatform platform() {
        return LivePlatform.MOCK;
    }

    @Override
    public boolean canSendDanmaku() {
        return true;
    }

    @Override
    public DanmakuSendResult sendDanmaku(DanmakuSendRequest request) {
        return DanmakuSendResult.sent("mock-" + UUID.randomUUID());
    }
}
