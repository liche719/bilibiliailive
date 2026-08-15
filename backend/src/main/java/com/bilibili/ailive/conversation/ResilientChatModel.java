package com.bilibili.ailive.conversation;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

final class ResilientChatModel implements ChatModel {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(25);
    private final ChatModel delegate;
    private final LiveModelRuntimeState runtimeState;
    private final Timer roundTripTimer;
    private boolean halfOpenProbeRunning;

    ResilientChatModel(ChatModel delegate, LiveModelRuntimeState runtimeState, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.runtimeState = runtimeState;
        this.roundTripTimer = Timer.builder("ai.live.model.roundtrip")
                .description("Physical model request duration including one transient retry")
                .register(meterRegistry);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return chat(chatRequest, ChatRequestOptions.EMPTY);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest, ChatRequestOptions options) {
        acquireCircuitPermission();
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = invokeWithTransientRetry(chatRequest, options);
            runtimeState.succeeded(Duration.ofNanos(System.nanoTime() - startedAt));
            closeHalfOpenProbe();
            return response;
        } catch (RuntimeException failure) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            int failures = runtimeState.failed(duration, failure);
            if (failures >= FAILURE_THRESHOLD) {
                runtimeState.openCircuit(OPEN_DURATION);
            }
            closeHalfOpenProbe();
            throw failure;
        } finally {
            roundTripTimer.record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        return chat(chatRequest);
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    ChatModel delegate() {
        return delegate;
    }

    private ChatResponse invokeWithTransientRetry(ChatRequest request, ChatRequestOptions options) {
        try {
            return delegate.chat(request, options);
        } catch (RuntimeException firstFailure) {
            if (!ModelFailureClassifier.retryable(firstFailure)) {
                throw firstFailure;
            }
            sleepBeforeRetry();
            try {
                return delegate.chat(request, options);
            } catch (RuntimeException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private synchronized void acquireCircuitPermission() {
        LiveModelRuntimeState.Snapshot snapshot = runtimeState.snapshot();
        if (snapshot.circuitState() == LiveModelRuntimeState.CircuitState.CLOSED) {
            return;
        }
        if (snapshot.circuitState() == LiveModelRuntimeState.CircuitState.OPEN
                && snapshot.circuitOpenUntil() != null
                && Instant.now().isBefore(snapshot.circuitOpenUntil())) {
            runtimeState.rejectedByCircuit();
            throw new ModelCircuitOpenException();
        }
        if (halfOpenProbeRunning) {
            runtimeState.rejectedByCircuit();
            throw new ModelCircuitOpenException();
        }
        halfOpenProbeRunning = true;
        runtimeState.halfOpen();
    }

    private synchronized void closeHalfOpenProbe() {
        halfOpenProbeRunning = false;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(300, 801));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model retry was interrupted", exception);
        }
    }
}
