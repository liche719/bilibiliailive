package com.bilibili.ailive.liveplatform.bilibili;

import com.bilibili.ailive.liveplatform.DanmakuSendRequest;
import com.bilibili.ailive.liveplatform.DanmakuSendResult;
import com.bilibili.ailive.liveplatform.LivePlatform;
import com.bilibili.ailive.liveplatform.LivePlatformGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.live-platform.bilibili.open-live-enabled",
        havingValue = "false",
        matchIfMissing = true
)
class DisabledBilibiliLivePlatformGateway implements LivePlatformGateway {

    @Override
    public LivePlatform platform() {
        return LivePlatform.BILIBILI;
    }

    @Override
    public boolean canSendDanmaku() {
        return false;
    }

    @Override
    public DanmakuSendResult sendDanmaku(DanmakuSendRequest request) {
        return DanmakuSendResult.failed("哔哩哔哩发送能力尚未认证接入");
    }
}
