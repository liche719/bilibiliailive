package com.bilibili.ailive.liveplatform.bilibili;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliReconnectPolicyTest {

    @Test
    void detectsAWebSocketThatMissedThreeHeartbeatResponses() {
        Instant lastActivity = Instant.parse("2026-08-10T10:00:00Z");

        assertFalse(BilibiliReconnectPolicy.socketActivityTimedOut(
                lastActivity, lastActivity.plusSeconds(60), Duration.ofSeconds(20)));
        assertTrue(BilibiliReconnectPolicy.socketActivityTimedOut(
                lastActivity, lastActivity.plusSeconds(61), Duration.ofSeconds(20)));
    }

    @Test
    void capsSessionRestartBackoffAtOneMinute() {
        assertEquals(5_000, BilibiliReconnectPolicy.sessionRestartDelayMillis(Duration.ofSeconds(5), 1));
        assertEquals(40_000, BilibiliReconnectPolicy.sessionRestartDelayMillis(Duration.ofSeconds(5), 4));
        assertEquals(60_000, BilibiliReconnectPolicy.sessionRestartDelayMillis(Duration.ofSeconds(5), 6));
        assertEquals(60_000, BilibiliReconnectPolicy.sessionRestartDelayMillis(Duration.ofSeconds(5), 100));
    }
}
