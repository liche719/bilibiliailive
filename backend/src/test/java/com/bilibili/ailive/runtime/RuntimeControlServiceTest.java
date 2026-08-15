package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;
import com.bilibili.ailive.overlay.OverlayHub;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeControlServiceTest {

    @Test
    void restoresTheLatestPersistedPauseState() {
        RuntimeControlEventRepository repository = mock(RuntimeControlEventRepository.class);
        when(repository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(
                new RuntimeControlEvent(
                        true,
                        LiveOutputMode.OVERLAY_AND_DANMAKU,
                        "local-operator",
                        Instant.parse("2026-08-10T00:00:00Z")
                )
        ));
        RuntimeControlService service = service(repository);

        service.restoreLastState();

        assertTrue(service.isPaused());
        org.junit.jupiter.api.Assertions.assertEquals(LiveOutputMode.OVERLAY_AND_DANMAKU, service.outputMode());
    }

    @Test
    void persistsOnlyRealStateChanges() {
        RuntimeControlEventRepository repository = mock(RuntimeControlEventRepository.class);
        when(repository.saveAndFlush(any(RuntimeControlEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeControlService service = service(repository);

        assertTrue(service.pause().paused());
        assertTrue(service.pause().paused());
        assertFalse(service.resume().paused());

        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(any(RuntimeControlEvent.class));
    }

    @Test
    void selectsThePausedActionWithoutExecutingTheRunningAction() {
        RuntimeControlEventRepository repository = mock(RuntimeControlEventRepository.class);
        when(repository.saveAndFlush(any(RuntimeControlEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeControlService service = service(repository);
        service.pause();
        Runnable runningAction = mock(Runnable.class);

        String result = service.executeIfRunning(
                () -> {
                    runningAction.run();
                    return "running";
                },
                () -> "paused"
        );

        org.junit.jupiter.api.Assertions.assertEquals("paused", result);
        verify(runningAction, never()).run();
    }

    @Test
    void clearsTheOverlayWhenPausing() {
        RuntimeControlEventRepository repository = mock(RuntimeControlEventRepository.class);
        OverlayHub overlayHub = mock(OverlayHub.class);
        when(repository.saveAndFlush(any(RuntimeControlEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeControlService service = new RuntimeControlService(
                repository,
                overlayHub,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );

        service.pause();

        verify(overlayHub).clear();
    }

    @Test
    void persistsOutputModeWithoutChangingPauseState() {
        RuntimeControlEventRepository repository = mock(RuntimeControlEventRepository.class);
        when(repository.saveAndFlush(any(RuntimeControlEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeControlService service = service(repository);

        RuntimeControlResponse response = service.changeOutputMode(LiveOutputMode.OVERLAY_AND_DANMAKU);

        assertFalse(response.paused());
        org.junit.jupiter.api.Assertions.assertEquals(LiveOutputMode.OVERLAY_AND_DANMAKU, response.outputMode());
        verify(repository).saveAndFlush(any(RuntimeControlEvent.class));
    }

    private static RuntimeControlService service(RuntimeControlEventRepository repository) {
        return new RuntimeControlService(
                repository,
                mock(OverlayHub.class),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
    }
}
