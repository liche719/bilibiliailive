package com.bilibili.ailive.conversation;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class LiveModelRuntimeState {

    public enum ActiveApiMode {
        UNKNOWN,
        RESPONSES,
        CHAT_COMPLETIONS
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String modelName;
    private final LangChain4jOpenAiProperties.ApiMode configuredApiMode;
    private ActiveApiMode activeApiMode = ActiveApiMode.UNKNOWN;
    private CircuitState circuitState = CircuitState.CLOSED;
    private Instant circuitOpenUntil;
    private Instant lastModelCallAt;
    private Long lastModelDurationMs;
    private String lastModelError;
    private int consecutiveModelFailures;

    LiveModelRuntimeState(LangChain4jOpenAiProperties properties) {
        LangChain4jOpenAiProperties.ChatModelProperties model = properties.chatModel();
        this.modelName = model.modelName();
        this.configuredApiMode = model.apiMode();
        if (configuredApiMode == LangChain4jOpenAiProperties.ApiMode.RESPONSES) {
            activeApiMode = ActiveApiMode.RESPONSES;
        } else if (configuredApiMode == LangChain4jOpenAiProperties.ApiMode.CHAT_COMPLETIONS) {
            activeApiMode = ActiveApiMode.CHAT_COMPLETIONS;
        }
    }

    synchronized void selected(ActiveApiMode apiMode) {
        this.activeApiMode = apiMode;
    }

    synchronized void succeeded(Duration duration) {
        lastModelCallAt = Instant.now();
        lastModelDurationMs = duration.toMillis();
        lastModelError = null;
        consecutiveModelFailures = 0;
        circuitState = CircuitState.CLOSED;
        circuitOpenUntil = null;
    }

    synchronized int failed(Duration duration, Throwable failure) {
        lastModelCallAt = Instant.now();
        lastModelDurationMs = duration.toMillis();
        lastModelError = ModelFailureClassifier.displayMessage(failure);
        return ++consecutiveModelFailures;
    }

    synchronized void openCircuit(Duration duration) {
        circuitState = CircuitState.OPEN;
        circuitOpenUntil = Instant.now().plus(duration);
    }

    synchronized void halfOpen() {
        circuitState = CircuitState.HALF_OPEN;
    }

    synchronized void rejectedByCircuit() {
        lastModelCallAt = Instant.now();
        lastModelDurationMs = 0L;
        lastModelError = "模型服务连续失败，正在等待自动恢复";
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                modelName,
                configuredApiMode,
                activeApiMode,
                lastModelCallAt,
                lastModelDurationMs,
                lastModelError,
                consecutiveModelFailures,
                circuitState,
                circuitOpenUntil
        );
    }

    public record Snapshot(
            String modelName,
            LangChain4jOpenAiProperties.ApiMode configuredApiMode,
            ActiveApiMode activeApiMode,
            Instant lastModelCallAt,
            Long lastModelDurationMs,
            String lastModelError,
            int consecutiveModelFailures,
            CircuitState circuitState,
            Instant circuitOpenUntil
    ) {
    }
}
