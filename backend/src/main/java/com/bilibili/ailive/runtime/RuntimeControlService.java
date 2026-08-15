package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;

import com.bilibili.ailive.overlay.OverlayHub;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

@Service
public class RuntimeControlService {

    private static final String LOCAL_OPERATOR = "local-operator";

    private final RuntimeControlEventRepository repository;
    private final Clock clock;
    private final OverlayHub overlayHub;
    private volatile RuntimeControlResponse current = new RuntimeControlResponse(
            false,
            LiveOutputMode.OVERLAY_ONLY,
            "system-default",
            Instant.EPOCH
    );

    @Autowired
    RuntimeControlService(RuntimeControlEventRepository repository, OverlayHub overlayHub) {
        this(repository, overlayHub, Clock.systemUTC());
    }

    RuntimeControlService(RuntimeControlEventRepository repository, OverlayHub overlayHub, Clock clock) {
        this.repository = repository;
        this.overlayHub = overlayHub;
        this.clock = clock;
    }

    @PostConstruct
    void restoreLastState() {
        repository.findFirstByOrderByCreatedAtDesc()
                .map(RuntimeControlService::toResponse)
                .ifPresent(response -> current = response);
    }

    public boolean isPaused() {
        return current.paused();
    }

    public RuntimeControlResponse current() {
        return current;
    }

    public LiveOutputMode outputMode() {
        return current.outputMode();
    }

    public synchronized RuntimeControlResponse pause() {
        return changeState(true);
    }

    public synchronized RuntimeControlResponse resume() {
        return changeState(false);
    }

    public synchronized RuntimeControlResponse changeOutputMode(LiveOutputMode outputMode) {
        if (outputMode == null) {
            throw new IllegalArgumentException("Output mode is required");
        }
        if (current.outputMode() == outputMode) {
            return current;
        }
        return persist(current.paused(), outputMode);
    }

    public synchronized <T> T executeIfRunning(Supplier<T> runningAction, Supplier<T> pausedAction) {
        return current.paused() ? pausedAction.get() : runningAction.get();
    }

    private RuntimeControlResponse changeState(boolean paused) {
        if (current.paused() == paused) {
            return current;
        }
        RuntimeControlResponse updated = persist(paused, current.outputMode());
        if (paused) {
            overlayHub.clear();
        }
        return updated;
    }

    private RuntimeControlResponse persist(boolean paused, LiveOutputMode outputMode) {
        RuntimeControlEvent event = repository.saveAndFlush(
                new RuntimeControlEvent(paused, outputMode, LOCAL_OPERATOR, clock.instant())
        );
        current = toResponse(event);
        return current;
    }

    private static RuntimeControlResponse toResponse(RuntimeControlEvent event) {
        return new RuntimeControlResponse(
                event.isPaused(),
                event.getOutputMode(),
                event.getActor(),
                event.getCreatedAt()
        );
    }
}
