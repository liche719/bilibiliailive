package com.bilibili.ailive.liveplatform.bilibili;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.live-platform.bilibili.open-live-enabled",
        havingValue = "false",
        matchIfMissing = true
)
class DisabledBilibiliLiveEventConnector implements BilibiliLiveEventConnector {

    @Override
    public void connect() {
        throw new IllegalStateException("Bilibili Open Live is disabled; set BILIBILI_OPEN_LIVE_ENABLED=true after configuring official credentials");
    }

    @Override
    public void disconnect() {
    }

    @Override
    public BilibiliConnectionStatus status() {
        return BilibiliConnectionStatus.disabled();
    }
}
