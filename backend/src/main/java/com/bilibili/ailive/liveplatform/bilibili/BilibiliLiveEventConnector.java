package com.bilibili.ailive.liveplatform.bilibili;

public interface BilibiliLiveEventConnector {

    void connect();

    void disconnect();

    BilibiliConnectionStatus status();
}
