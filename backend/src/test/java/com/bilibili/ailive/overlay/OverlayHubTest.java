package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;
import com.bilibili.ailive.conversation.ReplyStatus;
import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import com.bilibili.ailive.shared.SseEmitterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverlayHubTest {

    @Test
    void publishesTheRealModelWaitingLifecycle() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        OverlayHub hub = new OverlayHub(new OverlayStreamProperties(Duration.ZERO, 4), registry);

        try {
            hub.replyReceived("message-1", "测试观众", "你好");
            hub.replyStarted("message-1", "测试观众", "你好");
            hub.replyFinished("message-1");

            verify(registry).send("overlay-reply-received", new OverlayReplyReceived("message-1", "测试观众", "你好"));
            verify(registry).send("overlay-reply-start", new OverlayReplyStart("message-1", "测试观众", "你好"));
            verify(registry).send("overlay-reply-finish", "message-1");
        } finally {
            hub.close();
        }
    }

    @Test
    void publishesReplyOutcomes() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        OverlayHub hub = new OverlayHub(new OverlayStreamProperties(Duration.ZERO, 4), registry);
        ReplyCandidateResponse outcome = candidate("B");

        try {
            hub.replyOutcome(outcome);

            verify(registry).send("overlay-reply-outcome", outcome);
        } finally {
            hub.close();
        }
    }

    @Test
    void publishesViewerWelcomeAsAnIndependentOverlayEvent() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        OverlayHub hub = new OverlayHub(new OverlayStreamProperties(Duration.ZERO, 4), registry);
        OverlayWelcome welcome = new OverlayWelcome(
                UUID.randomUUID(),
                "1000",
                List.of("小纸船"),
                1,
                "@小纸船，欢迎来到直播间～",
                5_000,
                Instant.parse("2026-08-12T00:00:00Z")
        );

        try {
            hub.welcome(welcome);

            verify(registry).send("overlay-welcome", welcome);
        } finally {
            hub.close();
        }
    }

    @Test
    void streamsModeratedRepliesInOrderWithoutSplittingUnicodeCodePoints() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        when(registry.subscriberCount()).thenReturn(1);
        OverlayHub hub = new OverlayHub(new OverlayStreamProperties(Duration.ZERO, 4), registry);
        ReplyCandidateResponse first = candidate("A😀");
        ReplyCandidateResponse second = candidate("B");

        try {
            hub.publish(first);
            hub.publish(second);

            ArgumentCaptor<String> eventNames = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
            verify(registry, timeout(2_000).times(7)).send(eventNames.capture(), payloads.capture());

            assertEquals(List.of(
                    "overlay-stream-start",
                    "overlay-stream",
                    "overlay-stream",
                    "overlay",
                    "overlay-stream-start",
                    "overlay-stream",
                    "overlay"
            ), eventNames.getAllValues());
            OverlayStreamUpdate firstCharacter = assertInstanceOf(
                    OverlayStreamUpdate.class,
                    payloads.getAllValues().get(1)
            );
            OverlayStreamStart firstStart = assertInstanceOf(
                    OverlayStreamStart.class,
                    payloads.getAllValues().get(0)
            );
            assertEquals(first.messageId(), firstStart.messageId());
            assertEquals(first.sourceText(), firstStart.sourceText());
            OverlayStreamUpdate emojiCompleted = assertInstanceOf(
                    OverlayStreamUpdate.class,
                    payloads.getAllValues().get(2)
            );
            assertEquals("A", firstCharacter.text());
            assertEquals("A😀", emojiCompleted.text());
            assertEquals(first, payloads.getAllValues().get(3));
            assertEquals(second, payloads.getAllValues().get(6));
        } finally {
            hub.close();
        }
    }

    @Test
    void clearCancelsTheActiveReplyAndDropsQueuedPlayback() throws InterruptedException {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        when(registry.subscriberCount()).thenReturn(1);
        OverlayHub hub = new OverlayHub(
                new OverlayStreamProperties(Duration.ofSeconds(1), 4),
                registry
        );
        ReplyCandidateResponse active = candidate("第一条较长回复");
        ReplyCandidateResponse queued = candidate("第二条回复");

        try {
            hub.publish(active);
            hub.publish(queued);
            verify(registry, timeout(1_000)).send(eq("overlay-stream"), any(OverlayStreamUpdate.class));

            hub.clear();

            verify(registry, timeout(1_000)).send("overlay-clear", "");
            Thread.sleep(100);
            verify(registry, never()).send(eq("overlay"), any(ReplyCandidateResponse.class));
            verify(registry, never()).send(
                    eq("overlay-stream-start"),
                    argThat(payload -> payload instanceof OverlayStreamStart start
                            && start.candidateId().equals(queued.id()))
            );
        } finally {
            hub.close();
        }
    }

    @Test
    void keepsTheLatestReplyWithoutBlockingWhenPlaybackQueueIsFull() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        when(registry.subscriberCount()).thenReturn(1);
        OverlayHub hub = new OverlayHub(
                new OverlayStreamProperties(Duration.ofMillis(50), 1),
                registry
        );
        ReplyCandidateResponse active = candidate("正在生成一条比较长的回复用于占用播放线程");
        ReplyCandidateResponse stale = candidate("已经过时的排队回复");
        ReplyCandidateResponse latest = candidate("最新回复");

        try {
            hub.publish(active);
            verify(registry, timeout(1_000)).send(
                    eq("overlay-stream-start"),
                    argThat(payload -> payload instanceof OverlayStreamStart start
                            && start.candidateId().equals(active.id()))
            );
            hub.publish(stale);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> hub.publish(latest));

            verify(registry, timeout(3_000)).send(
                    eq("overlay-stream-start"),
                    argThat(payload -> payload instanceof OverlayStreamStart start
                            && start.candidateId().equals(latest.id()))
            );
            verify(registry, never()).send(
                    eq("overlay-stream-start"),
                    argThat(payload -> payload instanceof OverlayStreamStart start
                            && start.candidateId().equals(stale.id()))
            );
        } finally {
            hub.close();
        }
    }

    private static ReplyCandidateResponse candidate(String text) {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        return new ReplyCandidateResponse(
                UUID.randomUUID(),
                "MOCK",
                "1000",
                "viewer-1",
                "测试观众",
                UUID.randomUUID().toString(),
                "你好",
                text,
                null,
                DanmakuDeliveryStatus.NOT_REQUESTED,
                null,
                null,
                ReplyStatus.AUTO_PUBLISHED,
                null,
                1L,
                now,
                now
        );
    }
}
