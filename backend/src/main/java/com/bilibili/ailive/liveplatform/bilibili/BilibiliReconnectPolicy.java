package com.bilibili.ailive.liveplatform.bilibili;

import java.time.Duration;
import java.time.Instant;

final class BilibiliReconnectPolicy {

    private static final int MISSED_HEARTBEATS_BEFORE_RECONNECT = 3;
    private static final Duration MAX_RESTART_DELAY = Duration.ofMinutes(1);

    private BilibiliReconnectPolicy() {
    }

    static boolean socketActivityTimedOut(Instant lastActivity, Instant now, Duration heartbeatInterval) {
        return Duration.between(lastActivity, now)
                .compareTo(heartbeatInterval.multipliedBy(MISSED_HEARTBEATS_BEFORE_RECONNECT)) > 0;
    }

    static long sessionRestartDelayMillis(Duration reconnectDelay, int backoffLevel) {
        long baseDelay = reconnectDelay.toMillis();
        long cap = Math.max(baseDelay, MAX_RESTART_DELAY.toMillis());
        long factor = 1L << Math.min(Math.max(backoffLevel - 1, 0), 6);
        return baseDelay > cap / factor ? cap : Math.min(baseDelay * factor, cap);
    }
}
