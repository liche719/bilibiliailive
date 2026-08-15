package com.bilibili.ailive.conversation;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import com.bilibili.ailive.liveplatform.DanmakuDispatchService;
import com.bilibili.ailive.liveplatform.DanmakuDispatchResult;
import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import com.bilibili.ailive.liveplatform.LiveOutputMode;
import com.bilibili.ailive.overlay.OverlayPublisher;
import com.bilibili.ailive.shared.SseHub;
import com.bilibili.ailive.runtime.RuntimeControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplyWorkflowServiceTest {

    @Mock
    private LiveHostConversationService liveHostConversationService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ReplyCandidateRepository repository;

    @Mock
    private OverlayPublisher overlayPublisher;

    @Mock
    private RedisReplyAdmissionService replyAdmissionService;

    @Mock
    private RoomReplyScheduler roomReplyScheduler;

    @Mock
    private RuntimeControlService runtimeControlService;

    @Mock
    private DanmakuDispatchService danmakuDispatchService;

    @Mock
    private LiveReplyMetrics metrics;

    @Mock
    private RoomConversationContextStore roomContextStore;

    @Mock
    private LiveHostProfileService liveHostProfileService;

    @BeforeEach
    void runScheduledTasksImmediately() {
        lenient().when(roomReplyScheduler.executeOrdered(any(String.class), any(), any())).thenAnswer(invocation -> {
            Supplier<?> generation = invocation.getArgument(1);
            Function<Object, ?> commit = invocation.getArgument(2);
            return commit.apply(generation.get());
        });
        lenient().when(metrics.recordProcessing(any())).thenAnswer(invocation -> {
            Supplier<?> processing = invocation.getArgument(0);
            return processing.get();
        });
        lenient().when(runtimeControlService.executeIfRunning(any(), any())).thenAnswer(invocation -> {
            Supplier<?> runningAction = invocation.getArgument(0);
            return runningAction.get();
        });
        lenient().when(liveHostProfileService.resolve("1000"))
                .thenReturn(LiveHostProfileSnapshot.defaults("1000"));
    }

    @Test
    void doesNotPublishWhenModelReturnsBlankText() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request)).thenReturn(ReplyAdmissionDecision.allow());
        when(liveHostConversationService.replyStreaming(any(ReplyRequest.class), any(LiveHostProfileSnapshot.class)))
                .thenReturn(new LiveHostReply("  ", null, false));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.MODEL_FAILED, response.status());
        assertNull(response.candidateText());
        ArgumentCaptor<ReplyCandidate> savedCandidate = ArgumentCaptor.forClass(ReplyCandidate.class);
        verify(repository).saveAndFlush(savedCandidate.capture());
        assertEquals(ReplyStatus.MODEL_FAILED, savedCandidate.getValue().getStatus());
        verify(overlayPublisher).replyReceived("message-1", "viewer-1", "你好");
        verify(overlayPublisher).replyOutcome(response);
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void doesNotPublishWhenOutputModerationBlocksTheReply() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request)).thenReturn(ReplyAdmissionDecision.allow());
        when(liveHostConversationService.replyStreaming(any(ReplyRequest.class), any(LiveHostProfileSnapshot.class)))
                .thenThrow(new OutputModerationException("模型回复命中本地安全规则"));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.BLOCKED, response.status());
        assertEquals("模型回复命中本地安全规则", response.decisionReason());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void skipsTheModelWhenRedisAdmissionRejectsTheMessage() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request))
                .thenReturn(ReplyAdmissionDecision.reject("用户发言过于频繁，本条未调用模型"));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.RATE_LIMITED, response.status());
        verify(overlayPublisher).replyReceived("message-1", "viewer-1", "你好");
        verify(overlayPublisher).replyOutcome(response);
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void blocksRoomSpecificForbiddenTopicsBeforeCallingTheModel() {
        ReplyRequest request = request("message-1", "聊聊危险挑战");
        LiveHostProfileSnapshot profile = new LiveHostProfileSnapshot(
                "1000",
                "小航",
                "科技主播",
                "",
                "自然",
                120,
                "危险挑战",
                "欢迎",
                false,
                2,
                Instant.parse("2026-08-10T00:00:00Z")
        );
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("聊聊危险挑战")).thenReturn(ModerationOutcome.allow());
        when(liveHostProfileService.resolve("1000")).thenReturn(profile);
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.BLOCKED, response.status());
        verify(replyAdmissionService, never()).evaluate(any());
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(roomContextStore, never()).observe(any());
    }

    @Test
    void recordsAnOverloadedOutcomeWhenTheRoomQueueIsFull() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(roomReplyScheduler.executeOrdered(any(String.class), any(), any()))
                .thenThrow(new RoomQueueFullException());
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.OVERLOADED, response.status());
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void recordsAProcessingFailureWhenRedisAdmissionIsUnavailable() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request)).thenThrow(new IllegalStateException("redis unavailable"));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.PROCESSING_FAILED, response.status());
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void recordsPausedWithoutCallingAdmissionOrTheModel() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(runtimeControlService.isPaused()).thenReturn(true);
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.PAUSED, response.status());
        verify(replyAdmissionService, never()).evaluate(any());
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void returnsAnExistingReplyWithoutCallingTheModelAgain() {
        ReplyRequest request = request("message-1", "你好");
        ReplyCandidate existing = ReplyCandidate.autoPublished(request, new LiveHostReply("你好呀", null, false), 1);
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.of(existing));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(existing.getId(), response.id());
        verify(moderationService, never()).evaluateInput(any());
        verify(liveHostConversationService, never()).replyStreaming(any(), any());
        verify(repository, never()).saveAndFlush(any());
        verify(overlayPublisher, never()).publish(any());
    }

    @Test
    void keepsOneQueuePositionPerViewerWhileTheirReplyIsActive() throws Exception {
        ReplyRequest firstRequest = request("message-1", "第一个问题");
        ReplyRequest repeatedRequest = request("message-2", "连续追问");
        CountDownLatch firstScheduled = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(moderationService.evaluateInput("第一个问题")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(firstRequest)).thenReturn(ReplyAdmissionDecision.allow());
        when(liveHostConversationService.replyStreaming(any(ReplyRequest.class), any(LiveHostProfileSnapshot.class)))
                .thenReturn(new LiveHostReply("第一个回答", null, false));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomReplyScheduler.executeOrdered(any(String.class), any(), any())).thenAnswer(invocation -> {
            firstScheduled.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            Supplier<?> generation = invocation.getArgument(1);
            Function<Object, ?> commit = invocation.getArgument(2);
            return commit.apply(generation.get());
        });
        ReplyWorkflowService service = service();

        CompletableFuture<ReplyCandidateResponse> first = CompletableFuture.supplyAsync(
                () -> service.createCandidate(firstRequest)
        );
        assertTrue(firstScheduled.await(2, TimeUnit.SECONDS));

        ReplyCandidateResponse repeated = service.createCandidate(repeatedRequest);
        releaseFirst.countDown();

        assertEquals(ReplyStatus.RATE_LIMITED, repeated.status());
        assertEquals("你已经有一条弹幕正在排队或回复，本条未重复占用位置", repeated.decisionReason());
        assertEquals(ReplyStatus.AUTO_PUBLISHED, first.get(2, TimeUnit.SECONDS).status());
        verify(liveHostConversationService, times(1)).replyStreaming(any(), any());
    }

    @Test
    void publishesOverlayAndSendsDanmakuInDualChannelMode() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request)).thenReturn(ReplyAdmissionDecision.allow());
        when(liveHostConversationService.replyStreaming(any(ReplyRequest.class), any(LiveHostProfileSnapshot.class)))
                .thenReturn(new LiveHostReply("你好，欢迎来到直播间！", "欢迎来到直播间～", true));
        when(runtimeControlService.outputMode()).thenReturn(LiveOutputMode.OVERLAY_AND_DANMAKU);
        when(danmakuDispatchService.dispatch("MOCK", "1000", "欢迎来到直播间～"))
                .thenReturn(DanmakuDispatchResult.sent("mock-message"));
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.AUTO_PUBLISHED, response.status());
        assertEquals(DanmakuDeliveryStatus.SENT, response.danmakuStatus());
        assertEquals("mock-message", response.danmakuPlatformMessageId());
        verify(overlayPublisher).publish(any());
        verify(overlayPublisher).replyReceived("message-1", "viewer-1", "你好");
        verify(overlayPublisher, never()).replyOutcome(any());
        verify(danmakuDispatchService).dispatch("MOCK", "1000", "欢迎来到直播间～");
        verify(roomContextStore).observe(request);
        verify(roomContextStore).attachHostReply(request, "你好，欢迎来到直播间！");
    }

    @Test
    void keepsOverlayButSkipsDanmakuInOverlayOnlyMode() {
        ReplyRequest request = request("message-1", "你好");
        when(repository.findByPlatformAndRoomIdAndMessageId("MOCK", "1000", "message-1"))
                .thenReturn(Optional.empty());
        when(moderationService.evaluateInput("你好")).thenReturn(ModerationOutcome.allow());
        when(replyAdmissionService.evaluate(request)).thenReturn(ReplyAdmissionDecision.allow());
        when(liveHostConversationService.replyStreaming(any(ReplyRequest.class), any(LiveHostProfileSnapshot.class)))
                .thenReturn(new LiveHostReply("你好，欢迎来到直播间！", "欢迎来到直播间～", true));
        when(runtimeControlService.outputMode()).thenReturn(LiveOutputMode.OVERLAY_ONLY);
        when(repository.saveAndFlush(any(ReplyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplyWorkflowService service = service();

        ReplyCandidateResponse response = service.createCandidate(request);

        assertEquals(ReplyStatus.AUTO_PUBLISHED, response.status());
        assertEquals(DanmakuDeliveryStatus.SKIPPED, response.danmakuStatus());
        assertEquals("当前为仅 Overlay 模式", response.danmakuDecisionReason());
        verify(overlayPublisher).publish(any());
        verify(danmakuDispatchService, never()).dispatch(any(), any(), any());
    }

    private ReplyWorkflowService service() {
        return new ReplyWorkflowService(
                liveHostConversationService,
                moderationService,
                repository,
                new SseHub(),
                overlayPublisher,
                replyAdmissionService,
                roomReplyScheduler,
                runtimeControlService,
                danmakuDispatchService,
                metrics,
                roomContextStore,
                liveHostProfileService
        );
    }

    private static ReplyRequest request(String messageId, String messageText) {
        return new ReplyRequest(
                "MOCK",
                "1000",
                "viewer-1",
                messageId,
                messageText,
                Instant.parse("2026-08-10T00:00:00Z")
        );
    }
}
