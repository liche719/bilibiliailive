package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.LivePlatform;
import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import com.bilibili.ailive.overlay.OverlayPublisher;
import com.bilibili.ailive.overlay.OverlayWelcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewerWelcomeCoordinatorTest {

    private static final Instant START = Instant.parse("2026-08-12T00:00:00Z");

    private ViewerWelcomeAdmission admission;
    private LiveHostProfileService profileService;
    private RoomReplyScheduler replyScheduler;
    private OverlayPublisher overlayPublisher;
    private Clock clock;
    private AtomicReference<Instant> now;
    private ViewerWelcomeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        admission = mock(ViewerWelcomeAdmission.class);
        profileService = mock(LiveHostProfileService.class);
        replyScheduler = mock(RoomReplyScheduler.class);
        overlayPublisher = mock(OverlayPublisher.class);
        clock = mock(Clock.class);
        now = new AtomicReference<>(START);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(profileService.resolve("1000")).thenReturn(profile());
        coordinator = coordinator(admission);
    }

    @Test
    void welcomesOneNamedViewerAfterTheAggregationWindow() {
        when(admission.admit(event("viewer-1", "小纸船"), "game-1")).thenReturn(true);

        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        advance(Duration.ofSeconds(7));
        coordinator.flushReady();

        ArgumentCaptor<OverlayWelcome> welcome = ArgumentCaptor.forClass(OverlayWelcome.class);
        verify(overlayPublisher).welcome(welcome.capture());
        assertEquals(1, welcome.getValue().totalViewers());
        assertEquals("@小纸船，欢迎来到直播间～", welcome.getValue().text());
    }

    @Test
    void batchesSeveralViewersIntoOneWelcome() {
        when(admission.admit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("game-1")))
                .thenReturn(true);

        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        coordinator.accept(event("viewer-2", "晨风"), "game-1");
        coordinator.accept(event("viewer-3", "薄荷"), "game-1");
        advance(Duration.ofSeconds(7));
        coordinator.flushReady();

        ArgumentCaptor<OverlayWelcome> welcome = ArgumentCaptor.forClass(OverlayWelcome.class);
        verify(overlayPublisher).welcome(welcome.capture());
        assertEquals(3, welcome.getValue().totalViewers());
        assertTrue(welcome.getValue().text().contains("@小纸船、@晨风、@薄荷"));
    }

    @Test
    void doesNotWelcomeTheSameViewerTwiceInOneSession() {
        Set<String> admitted = new HashSet<>();
        ViewerWelcomeAdmission sessionAdmission = (event, sessionId) -> admitted.add(sessionId + ":" + event.viewerId());
        coordinator = coordinator(sessionAdmission);

        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        advance(Duration.ofSeconds(7));
        coordinator.flushReady();

        ArgumentCaptor<OverlayWelcome> welcome = ArgumentCaptor.forClass(OverlayWelcome.class);
        verify(overlayPublisher).welcome(welcome.capture());
        assertEquals(1, welcome.getValue().totalViewers());
    }

    @Test
    void delaysWelcomeUntilTheActiveReplyFinishes() {
        when(admission.admit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("game-1")))
                .thenReturn(true);
        when(replyScheduler.isBusy("BILIBILI:1000")).thenReturn(true, false);

        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        advance(Duration.ofSeconds(7));
        coordinator.flushReady();
        verify(overlayPublisher, never()).welcome(org.mockito.ArgumentMatchers.any());

        advance(Duration.ofSeconds(2));
        coordinator.flushReady();
        verify(overlayPublisher).welcome(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dropsAnExpiredWelcomeInsteadOfInterruptingAReply() {
        when(admission.admit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("game-1")))
                .thenReturn(true);
        when(replyScheduler.isBusy("BILIBILI:1000")).thenReturn(true);

        coordinator.accept(event("viewer-1", "小纸船"), "game-1");
        advance(Duration.ofSeconds(16));
        coordinator.flushReady();
        reset(replyScheduler);
        advance(Duration.ofSeconds(1));
        coordinator.flushReady();

        verify(overlayPublisher, never()).welcome(org.mockito.ArgumentMatchers.any());
    }

    private ViewerWelcomeCoordinator coordinator(ViewerWelcomeAdmission welcomeAdmission) {
        return new ViewerWelcomeCoordinator(
                welcomeAdmission,
                properties(),
                profileService,
                replyScheduler,
                overlayPublisher,
                clock
        );
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    private static ViewerEnteredEvent event(String viewerId, String viewerName) {
        return new ViewerEnteredEvent(LivePlatform.BILIBILI, "1000", viewerId, viewerName, START);
    }

    private static ViewerWelcomeProperties properties() {
        return new ViewerWelcomeProperties(
                true,
                Duration.ofSeconds(6),
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
                Duration.ofHours(12),
                Duration.ofSeconds(5),
                3,
                20,
                "test:welcome"
        );
    }

    private static LiveHostProfileSnapshot profile() {
        return new LiveHostProfileSnapshot(
                "1000",
                "AI 主播",
                "友好",
                "",
                "简洁",
                120,
                "",
                "欢迎来到直播间",
                false,
                1,
                START
        );
    }
}
