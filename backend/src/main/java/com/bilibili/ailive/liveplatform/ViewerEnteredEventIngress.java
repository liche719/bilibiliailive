package com.bilibili.ailive.liveplatform;

public interface ViewerEnteredEventIngress {

    void accept(ViewerEnteredEvent event, String sessionId);
}
