package com.bilibili.ailive.liveplatform.bilibili;

import java.time.Instant;

public record BilibiliConnectionStatus(
        BilibiliConnectionState state,
        Long roomId,
        String gameId,
        Instant connectedAt,
        String lastError
) {

    static BilibiliConnectionStatus disabled() {
        return new BilibiliConnectionStatus(BilibiliConnectionState.DISABLED, null, null, null, null);
    }

    static BilibiliConnectionStatus notConfigured() {
        return new BilibiliConnectionStatus(BilibiliConnectionState.NOT_CONFIGURED, null, null, null, null);
    }

    static BilibiliConnectionStatus disconnected() {
        return new BilibiliConnectionStatus(BilibiliConnectionState.DISCONNECTED, null, null, null, null);
    }
}
