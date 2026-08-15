package com.bilibili.ailive.conversation;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ResilientStreamingChatModel implements StreamingChatModel {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(25);
    private final StreamingChatModel delegate;
    private final LiveModelRuntimeState runtimeState;
    private final Timer roundTripTimer;
    private boolean halfOpenProbeRunning;

    ResilientStreamingChatModel(
            StreamingChatModel delegate,
            LiveModelRuntimeState runtimeState,
            MeterRegistry meterRegistry
    ) {
        this.delegate = delegate;
        this.runtimeState = runtimeState;
        this.roundTripTimer = Timer.builder("ai.live.model.streaming.roundtrip")
                .description("Streaming model request duration including one transient retry")
                .register(meterRegistry);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            acquireCircuitPermission();
        } catch (RuntimeException failure) {
            handler.onError(failure);
            return;
        }
        CallState callState = new CallState(System.nanoTime());
        invoke(request, handler, callState, 0);
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    StreamingChatModel delegate() {
        return delegate;
    }

    private void invoke(
            ChatRequest request,
            StreamingChatResponseHandler handler,
            CallState callState,
            int attempt
    ) {
        AtomicBoolean emitted = new AtomicBoolean();
        try {
            delegate.chat(request, new ForwardingStreamingChatResponseHandler(handler) {
                @Override
                public void onPartialResponse(String partialResponse) {
                    emitted.set(true);
                    super.onPartialResponse(partialResponse);
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                    emitted.set(true);
                    super.onPartialResponse(partialResponse, context);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    emitted.set(true);
                    super.onPartialThinking(partialThinking);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                    emitted.set(true);
                    super.onPartialThinking(partialThinking, context);
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    emitted.set(true);
                    super.onPartialToolCall(partialToolCall);
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                    emitted.set(true);
                    super.onPartialToolCall(partialToolCall, context);
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    emitted.set(true);
                    super.onCompleteToolCall(completeToolCall);
                }

                @Override
                public void onUnmappedRawEvent(Object rawEvent) {
                    emitted.set(true);
                    super.onUnmappedRawEvent(rawEvent);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    if (callState.terminal.compareAndSet(false, true)) {
                        finishSuccess(callState.startedAtNanos);
                        delegate.onCompleteResponse(completeResponse);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (attempt == 0 && !emitted.get() && ModelFailureClassifier.retryable(error)) {
                        scheduleRetry(request, delegate, callState);
                        return;
                    }
                    if (callState.terminal.compareAndSet(false, true)) {
                        finishFailure(callState.startedAtNanos, error);
                        delegate.onError(error);
                    }
                }
            });
        } catch (RuntimeException failure) {
            if (attempt == 0 && ModelFailureClassifier.retryable(failure)) {
                scheduleRetry(request, handler, callState);
            } else if (callState.terminal.compareAndSet(false, true)) {
                finishFailure(callState.startedAtNanos, failure);
                handler.onError(failure);
            }
        }
    }

    private void scheduleRetry(
            ChatRequest request,
            StreamingChatResponseHandler handler,
            CallState callState
    ) {
        long delay = ThreadLocalRandom.current().nextLong(300, 801);
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> invoke(request, handler, callState, 1));
    }

    private void finishSuccess(long startedAtNanos) {
        Duration duration = Duration.ofNanos(System.nanoTime() - startedAtNanos);
        runtimeState.succeeded(duration);
        roundTripTimer.record(duration);
        closeHalfOpenProbe();
    }

    private void finishFailure(long startedAtNanos, Throwable failure) {
        Duration duration = Duration.ofNanos(System.nanoTime() - startedAtNanos);
        int failures = runtimeState.failed(duration, failure);
        if (failures >= FAILURE_THRESHOLD) {
            runtimeState.openCircuit(OPEN_DURATION);
        }
        roundTripTimer.record(duration);
        closeHalfOpenProbe();
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

    private static final class CallState {
        private final long startedAtNanos;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private CallState(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
    }
}
