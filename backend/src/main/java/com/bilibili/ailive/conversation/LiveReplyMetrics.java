package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.time.Duration;
import java.time.Instant;

@Component
class LiveReplyMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer replyGenerationTimer;
    private final Timer roomContextToolTimer;
    private final Timer webSearchToolTimer;
    private final Timer replyProcessingTimer;
    private final Timer replyEndToEndTimer;
    private final Timer roomContextReadTimer;
    private final Timer roomContextWriteTimer;

    LiveReplyMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.replyGenerationTimer = Timer.builder("ai.live.reply.generation")
                .description("Complete AI service reply generation duration, including tool calls")
                .register(meterRegistry);
        this.roomContextToolTimer = Timer.builder("ai.live.tools.room-context")
                .description("Room context tool execution duration")
                .register(meterRegistry);
        this.webSearchToolTimer = Timer.builder("ai.live.tools.web-search")
                .description("Internet search tool execution duration")
                .register(meterRegistry);
        this.replyProcessingTimer = Timer.builder("ai.live.reply.processing")
                .description("Reply processing duration after leaving the room queue")
                .register(meterRegistry);
        this.replyEndToEndTimer = Timer.builder("ai.live.reply.end-to-end")
                .description("Viewer message to persisted reply duration")
                .register(meterRegistry);
        this.roomContextReadTimer = Timer.builder("ai.live.redis.room-context.read")
                .description("Redis shared room context read duration")
                .register(meterRegistry);
        this.roomContextWriteTimer = Timer.builder("ai.live.redis.room-context.write")
                .description("Redis shared room context write duration")
                .register(meterRegistry);
    }

    <T> T recordRoomContextToolCall(Supplier<T> toolCall) {
        return roomContextToolTimer.record(toolCall);
    }

    <T> T recordWebSearchToolCall(Supplier<T> toolCall) {
        return webSearchToolTimer.record(toolCall);
    }

    <T> T recordModelCall(Supplier<T> modelCall) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return modelCall.get();
        } finally {
            sample.stop(replyGenerationTimer);
        }
    }

    <T> T recordProcessing(Supplier<T> processing) {
        return replyProcessingTimer.record(processing);
    }

    <T> T recordRoomContextRead(Supplier<T> read) {
        return roomContextReadTimer.record(read);
    }

    void recordRoomContextWrite(Runnable write) {
        roomContextWriteTimer.record(write);
    }

    void recordEndToEnd(Instant occurredAt) {
        Duration duration = Duration.between(occurredAt, Instant.now());
        replyEndToEndTimer.record(duration.isNegative() ? Duration.ZERO : duration);
    }

    void recordOutcome(ReplyStatus status) {
        meterRegistry.counter("ai.live.reply.outcomes", "status", status.name()).increment();
    }

    void recordAdmission(String outcome) {
        meterRegistry.counter("ai.live.reply.admission", "outcome", outcome).increment();
    }

    void recordDanmaku(DanmakuDeliveryStatus status) {
        meterRegistry.counter("ai.live.danmaku.delivery", "status", status.name()).increment();
    }
}
