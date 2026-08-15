package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;
import com.bilibili.ailive.conversation.LangChain4jOpenAiProperties;
import com.bilibili.ailive.conversation.LiveModelRuntimeState;

import java.time.Instant;

public record RuntimeStatusResponse(
        boolean modelConfigured,
        String modelName,
        LangChain4jOpenAiProperties.ApiMode configuredApiMode,
        LiveModelRuntimeState.ActiveApiMode activeApiMode,
        Instant lastModelCallAt,
        Long lastModelDurationMs,
        String lastModelError,
        int consecutiveModelFailures,
        LiveModelRuntimeState.CircuitState circuitState,
        Instant circuitOpenUntil,
        boolean bilibiliOpenLiveEnabled,
        boolean paused,
        LiveOutputMode outputMode,
        int activeRooms,
        int pendingReplies,
        int maxPendingPerRoom,
        int maxConcurrentPerRoom,
        int maxModelCallsPerWindow,
        int overlaySubscribers,
        int controlSubscribers,
        long recentlyActiveViewers,
        long observedSessionViewers,
        boolean audienceEstimated
) {
}
