package com.bilibili.ailive.liveplatform;

public record DanmakuSendResult(
        boolean sent,
        String platformMessageId,
        String reason
) {

    public static DanmakuSendResult sent(String platformMessageId) {
        return new DanmakuSendResult(true, platformMessageId, null);
    }

    public static DanmakuSendResult failed(String reason) {
        return new DanmakuSendResult(false, null, reason);
    }
}
