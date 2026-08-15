package com.bilibili.ailive.runtime;

import com.bilibili.ailive.conversation.LangChain4jOpenAiProperties;
import com.bilibili.ailive.conversation.LiveReplyPolicyProperties;
import com.bilibili.ailive.conversation.LiveModelRuntimeState;
import com.bilibili.ailive.conversation.ReplyQueueSnapshot;
import com.bilibili.ailive.conversation.RoomReplyScheduler;
import com.bilibili.ailive.overlay.OverlayHub;
import com.bilibili.ailive.shared.SseHub;
import com.bilibili.ailive.liveplatform.LiveAudienceSnapshot;
import com.bilibili.ailive.liveplatform.LiveAudienceTracker;
import com.bilibili.ailive.liveplatform.LivePlatform;
import com.bilibili.ailive.liveplatform.bilibili.BilibiliConnectionStatus;
import com.bilibili.ailive.liveplatform.bilibili.BilibiliLiveEventConnector;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
class RuntimeStatusController {

    private final LangChain4jOpenAiProperties properties;
    private final boolean bilibiliOpenLiveEnabled;
    private final RuntimeControlService runtimeControlService;
    private final RoomReplyScheduler roomReplyScheduler;
    private final LiveReplyPolicyProperties replyPolicy;
    private final OverlayHub overlayHub;
    private final SseHub sseHub;
    private final BilibiliLiveEventConnector bilibiliConnector;
    private final LiveAudienceTracker audienceTracker;
    private final LiveModelRuntimeState modelRuntimeState;

    RuntimeStatusController(
            LangChain4jOpenAiProperties properties,
            @Value("${app.live-platform.bilibili.open-live-enabled}") boolean bilibiliOpenLiveEnabled,
            RuntimeControlService runtimeControlService,
            RoomReplyScheduler roomReplyScheduler,
            LiveReplyPolicyProperties replyPolicy,
            OverlayHub overlayHub,
            SseHub sseHub,
            BilibiliLiveEventConnector bilibiliConnector,
            LiveAudienceTracker audienceTracker,
            LiveModelRuntimeState modelRuntimeState
    ) {
        this.properties = properties;
        this.bilibiliOpenLiveEnabled = bilibiliOpenLiveEnabled;
        this.runtimeControlService = runtimeControlService;
        this.roomReplyScheduler = roomReplyScheduler;
        this.replyPolicy = replyPolicy;
        this.overlayHub = overlayHub;
        this.sseHub = sseHub;
        this.bilibiliConnector = bilibiliConnector;
        this.audienceTracker = audienceTracker;
        this.modelRuntimeState = modelRuntimeState;
    }

    @GetMapping
    RuntimeStatusResponse status() {
        ReplyQueueSnapshot queue = roomReplyScheduler.snapshot();
        BilibiliConnectionStatus bilibiliStatus = bilibiliConnector.status();
        LiveAudienceSnapshot audience = bilibiliStatus.roomId() == null
                ? LiveAudienceSnapshot.unavailable()
                : audienceTracker.snapshot(
                        LivePlatform.BILIBILI,
                        Long.toString(bilibiliStatus.roomId()),
                        bilibiliStatus.gameId()
                );
        LiveModelRuntimeState.Snapshot model = modelRuntimeState.snapshot();
        return new RuntimeStatusResponse(
                properties.chatModel().isConfigured(),
                model.modelName(),
                model.configuredApiMode(),
                model.activeApiMode(),
                model.lastModelCallAt(),
                model.lastModelDurationMs(),
                model.lastModelError(),
                model.consecutiveModelFailures(),
                model.circuitState(),
                model.circuitOpenUntil(),
                bilibiliOpenLiveEnabled,
                runtimeControlService.isPaused(),
                runtimeControlService.outputMode(),
                queue.activeRooms(),
                queue.pendingMessages(),
                replyPolicy.maxPendingPerRoom(),
                replyPolicy.maxConcurrentPerRoom(),
                replyPolicy.maxModelCallsPerWindow(),
                overlayHub.subscriberCount(),
                sseHub.subscriberCount(),
                audience.recentlyActiveViewers(),
                audience.observedSessionViewers(),
                audience.estimated()
        );
    }

    @PostMapping("/pause")
    RuntimeControlResponse pause() {
        return runtimeControlService.pause();
    }

    @PostMapping("/resume")
    RuntimeControlResponse resume() {
        return runtimeControlService.resume();
    }

    @PostMapping("/output-mode")
    RuntimeControlResponse changeOutputMode(
            @Valid @org.springframework.web.bind.annotation.RequestBody OutputModeChangeRequest request
    ) {
        return runtimeControlService.changeOutputMode(request.outputMode());
    }
}
