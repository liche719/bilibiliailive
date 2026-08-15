package com.bilibili.ailive.liveplatform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundDanmakuEchoGuardTest {

    @Test
    void matchesBroadcasterEchoByPlatformMessageIdOrText() {
        OutboundDanmakuEchoGuard guard = guard();
        guard.record(LivePlatform.BILIBILI, "1000", "欢迎来到直播间～", "message-1");

        assertTrue(guard.isEcho(event("message-1", "不同文本", true)));
        assertTrue(guard.isEcho(event("message-2", "欢迎来到直播间～", true)));
        assertFalse(guard.isEcho(event("message-3", "欢迎来到直播间～", false)));
    }

    private static OutboundDanmakuEchoGuard guard() {
        return new OutboundDanmakuEchoGuard(
                new DanmakuProperties(40, Duration.ofSeconds(10), Duration.ofMinutes(2)),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static LiveChatEvent event(String messageId, String text, boolean broadcasterMessage) {
        return new LiveChatEvent(
                LivePlatform.BILIBILI,
                "1000",
                "broadcaster",
                messageId,
                text,
                Instant.parse("2026-08-10T00:00:01Z"),
                broadcasterMessage
        );
    }
}
