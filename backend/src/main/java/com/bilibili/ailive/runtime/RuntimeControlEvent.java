package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runtime_control_events")
class RuntimeControlEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private boolean paused;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private LiveOutputMode outputMode;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false)
    private Instant createdAt;

    protected RuntimeControlEvent() {
    }

    RuntimeControlEvent(boolean paused, LiveOutputMode outputMode, String actor, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.paused = paused;
        this.outputMode = outputMode;
        this.actor = actor;
        this.createdAt = createdAt;
    }

    boolean isPaused() {
        return paused;
    }

    LiveOutputMode getOutputMode() {
        return outputMode;
    }

    String getActor() {
        return actor;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
