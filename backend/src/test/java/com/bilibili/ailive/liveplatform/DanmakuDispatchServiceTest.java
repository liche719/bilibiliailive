package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmakuDispatchServiceTest {

    @Test
    void sendsAValidDanmakuThroughTheMatchingGateway() {
        LivePlatformGateway gateway = gateway();
        when(gateway.sendDanmaku(any())).thenReturn(DanmakuSendResult.sent("platform-message"));
        DanmakuDispatchService service = service(gateway);

        DanmakuDispatchResult result = service.dispatch("MOCK", "1000", "欢迎来到直播间～");

        assertEquals(DanmakuDeliveryStatus.SENT, result.status());
        assertEquals("platform-message", result.platformMessageId());
    }

    @Test
    void enforcesThePerRoomMinimumInterval() {
        LivePlatformGateway gateway = gateway();
        when(gateway.sendDanmaku(any())).thenReturn(DanmakuSendResult.sent("platform-message"));
        DanmakuDispatchService service = service(gateway);

        service.dispatch("MOCK", "1000", "第一条");
        DanmakuDispatchResult second = service.dispatch("MOCK", "1000", "第二条");

        assertEquals(DanmakuDeliveryStatus.SKIPPED, second.status());
        verify(gateway, org.mockito.Mockito.times(1)).sendDanmaku(any());
    }

    @Test
    void rejectsTextOverTheDanmakuLengthLimit() {
        LivePlatformGateway gateway = gateway();
        DanmakuDispatchService service = service(gateway);

        DanmakuDispatchResult result = service.dispatch("MOCK", "1000", "一".repeat(41));

        assertEquals(DanmakuDeliveryStatus.SKIPPED, result.status());
        verify(gateway, never()).sendDanmaku(any());
    }

    private static DanmakuDispatchService service(LivePlatformGateway gateway) {
        ModerationService moderationService = mock(ModerationService.class);
        when(moderationService.evaluateOutput(any())).thenReturn(ModerationOutcome.allow());
        return new DanmakuDispatchService(
                List.of(gateway),
                new DanmakuProperties(40, Duration.ofSeconds(10), Duration.ofMinutes(2)),
                moderationService,
                new OutboundDanmakuEchoGuard(
                        new DanmakuProperties(40, Duration.ofSeconds(10), Duration.ofMinutes(2)),
                        Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
                ),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static LivePlatformGateway gateway() {
        LivePlatformGateway gateway = mock(LivePlatformGateway.class);
        when(gateway.platform()).thenReturn(LivePlatform.MOCK);
        when(gateway.canSendDanmaku()).thenReturn(true);
        return gateway;
    }
}
