package com.bilibili.ailive.liveplatform;

public record DanmakuDispatchResult(
        DanmakuDeliveryStatus status,
        String platformMessageId,
        String reason
) {

    public static DanmakuDispatchResult skipped(String reason) {
        return new DanmakuDispatchResult(DanmakuDeliveryStatus.SKIPPED, null, reason);
    }

    public static DanmakuDispatchResult sent(String platformMessageId) {
        return new DanmakuDispatchResult(DanmakuDeliveryStatus.SENT, platformMessageId, null);
    }

    public static DanmakuDispatchResult failed(String reason) {
        return new DanmakuDispatchResult(DanmakuDeliveryStatus.FAILED, null, reason);
    }
}
