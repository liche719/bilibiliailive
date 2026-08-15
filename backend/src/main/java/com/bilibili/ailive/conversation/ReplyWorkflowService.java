package com.bilibili.ailive.conversation;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import com.bilibili.ailive.liveplatform.DanmakuDispatchResult;
import com.bilibili.ailive.liveplatform.DanmakuDispatchService;
import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import com.bilibili.ailive.liveplatform.LiveOutputMode;
import com.bilibili.ailive.overlay.OverlayPublisher;
import com.bilibili.ailive.runtime.RuntimeControlService;
import com.bilibili.ailive.shared.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ReplyWorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(ReplyWorkflowService.class);
    private static final String EMPTY_MODEL_RESPONSE_REASON = "模型未返回可展示的回复，未上屏";
    private static final String MODEL_UNAVAILABLE_REASON = "模型服务暂不可用，未上屏";
    private static final String PROCESSING_UNAVAILABLE_REASON = "回复控制服务暂不可用，本条未处理";
    private static final String ROOM_OVERLOADED_REASON = "直播间待处理消息已满，本条已丢弃";
    private static final String QUEUE_EXPIRED_REASON = "弹幕排队等待过久，本条已过期丢弃";
    private static final String VIEWER_ALREADY_WAITING_REASON = "你已经有一条弹幕正在排队或回复，本条未重复占用位置";
    private static final String PAUSED_REASON = "自动回复已紧急暂停，本条未上屏";
    private static final int MESSAGE_LOCK_STRIPES = 64;

    private final LiveHostConversationService liveHostConversationService;
    private final ModerationService moderationService;
    private final ReplyCandidateRepository repository;
    private final SseHub sseHub;
    private final OverlayPublisher overlayPublisher;
    private final RedisReplyAdmissionService replyAdmissionService;
    private final RoomReplyScheduler roomReplyScheduler;
    private final RuntimeControlService runtimeControlService;
    private final DanmakuDispatchService danmakuDispatchService;
    private final LiveReplyMetrics metrics;
    private final RoomConversationContextStore roomContextStore;
    private final LiveHostProfileService liveHostProfileService;
    private final ReentrantLock[] messageLocks = new ReentrantLock[MESSAGE_LOCK_STRIPES];
    private final Set<String> activeReplyViewers = ConcurrentHashMap.newKeySet();

    public ReplyWorkflowService(
            LiveHostConversationService liveHostConversationService,
            ModerationService moderationService,
            ReplyCandidateRepository repository,
            SseHub sseHub,
            OverlayPublisher overlayPublisher,
            RedisReplyAdmissionService replyAdmissionService,
            RoomReplyScheduler roomReplyScheduler,
            RuntimeControlService runtimeControlService,
            DanmakuDispatchService danmakuDispatchService,
            LiveReplyMetrics metrics,
            RoomConversationContextStore roomContextStore,
            LiveHostProfileService liveHostProfileService
    ) {
        this.liveHostConversationService = liveHostConversationService;
        this.moderationService = moderationService;
        this.repository = repository;
        this.sseHub = sseHub;
        this.overlayPublisher = overlayPublisher;
        this.replyAdmissionService = replyAdmissionService;
        this.roomReplyScheduler = roomReplyScheduler;
        this.runtimeControlService = runtimeControlService;
        this.danmakuDispatchService = danmakuDispatchService;
        this.metrics = metrics;
        this.roomContextStore = roomContextStore;
        this.liveHostProfileService = liveHostProfileService;
        for (int index = 0; index < messageLocks.length; index++) {
            messageLocks[index] = new ReentrantLock();
        }
    }

    public ReplyCandidateResponse createCandidate(ReplyRequest request) {
        ReentrantLock messageLock = messageLockFor(request);
        messageLock.lock();
        boolean viewerSlotAcquired = false;
        try {
            ReplyCandidateResponse existing = findExisting(request);
            if (existing != null) {
                return existing;
            }
            viewerSlotAcquired = activeReplyViewers.add(request.memoryId());
            if (!viewerSlotAcquired) {
                return saveAndPublish(
                        request,
                        ReplyCandidate.rateLimited(request, VIEWER_ALREADY_WAITING_REASON)
                );
            }
            overlayPublisher.replyReceived(request.messageId(), request.senderName(), request.messageText());
            ModerationOutcome moderation = moderationService.evaluateInput(request.messageText());
            if (!moderation.allowed()) {
                return saveAndPublish(request, ReplyCandidate.blocked(request, moderation.reason()));
            }
            LiveHostProfileSnapshot profile;
            try {
                profile = liveHostProfileService.resolve(request.roomId());
            } catch (RuntimeException exception) {
                logger.warn("Unable to load the live host profile for room {}", request.roomId(), exception);
                return saveAndPublish(request, ReplyCandidate.processingFailed(request, PROCESSING_UNAVAILABLE_REASON));
            }
            if (profile.containsForbiddenTopic(request.messageText())) {
                return saveAndPublish(
                        request,
                        ReplyCandidate.blocked(request, "消息涉及当前直播间禁止话题")
                );
            }
            roomContextStore.observe(request);
            try {
                return roomReplyScheduler.executeOrdered(
                        request.roomExecutionKey(),
                        () -> metrics.recordProcessing(() -> createReplyCandidate(request, profile)),
                        candidate -> commitCandidate(request, candidate)
                );
            } catch (RoomQueueFullException exception) {
                logger.warn("Reply queue is full for room {}; dropping message", request.roomId());
                return saveAndPublish(request, ReplyCandidate.overloaded(request, ROOM_OVERLOADED_REASON));
            } catch (RoomQueueExpiredException exception) {
                logger.warn("Reply expired in the queue for room {}; dropping message", request.roomId());
                return saveAndPublish(request, ReplyCandidate.overloaded(request, QUEUE_EXPIRED_REASON));
            }
        } finally {
            if (viewerSlotAcquired) {
                activeReplyViewers.remove(request.memoryId());
            }
            messageLock.unlock();
        }
    }

    public ReplyCandidateResponse recordIgnoredEcho(ReplyRequest request, String reason) {
        ReentrantLock messageLock = messageLockFor(request);
        messageLock.lock();
        try {
            ReplyCandidateResponse existing = findExisting(request);
            return existing != null
                    ? existing
                    : saveAndPublish(request, ReplyCandidate.echoIgnored(request, reason));
        } finally {
            messageLock.unlock();
        }
    }

    private ReplyCandidateResponse commitCandidate(ReplyRequest request, ReplyCandidate candidate) {
        if (candidate.getStatus() == ReplyStatus.AUTO_PUBLISHED) {
            return runtimeControlService.executeIfRunning(
                    () -> saveAndPublish(request, candidate),
                    () -> {
                        overlayPublisher.replyFinished(request.messageId());
                        return saveAndPublish(request, ReplyCandidate.paused(request, PAUSED_REASON));
                    }
            );
        }
        return saveAndPublish(request, candidate);
    }

    private ReplyCandidateResponse saveAndPublish(ReplyRequest request, ReplyCandidate candidate) {
        SavedReply savedReply = saveOrFindExisting(request, candidate);
        if (savedReply.created()) {
            if (savedReply.candidate().getStatus() == ReplyStatus.AUTO_PUBLISHED) {
                roomContextStore.attachHostReply(request, savedReply.candidate().getCandidateText());
                overlayPublisher.publish(ReplyCandidateResponse.from(savedReply.candidate()));
                dispatchDanmaku(savedReply.candidate());
            }
            ReplyCandidateResponse response = ReplyCandidateResponse.from(savedReply.candidate());
            if (response.status() != ReplyStatus.AUTO_PUBLISHED
                    && response.status() != ReplyStatus.ECHO_IGNORED) {
                overlayPublisher.replyOutcome(response);
            }
            metrics.recordOutcome(response.status());
            metrics.recordEndToEnd(request.occurredAt());
            sseHub.publish("candidate", response);
            return response;
        }
        return ReplyCandidateResponse.from(savedReply.candidate());
    }

    private void dispatchDanmaku(ReplyCandidate candidate) {
        if (candidate.getDanmakuStatus() != DanmakuDeliveryStatus.PENDING) {
            return;
        }
        DanmakuDispatchResult result = runtimeControlService.outputMode() == LiveOutputMode.OVERLAY_AND_DANMAKU
                ? danmakuDispatchService.dispatch(candidate.getPlatform(), candidate.getRoomId(), candidate.getDanmakuText())
                : DanmakuDispatchResult.skipped("当前为仅 Overlay 模式");
        candidate.recordDanmakuResult(result);
        repository.saveAndFlush(candidate);
        metrics.recordDanmaku(result.status());
    }

    @Transactional(readOnly = true)
    public List<ReplyCandidateResponse> listCandidates() {
        return repository.findTop100ByOrderByCreatedAtDesc().stream().map(ReplyCandidateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReplyCandidateResponse currentPublishedReply() {
        return repository.findFirstByStatusAndCandidateTextIsNotNullOrderByCreatedAtDesc(ReplyStatus.AUTO_PUBLISHED)
                .map(ReplyCandidateResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ReplyCandidateResponse> recentPublishedReplies() {
        List<ReplyCandidateResponse> newestFirst = repository
                .findTop6ByStatusAndCandidateTextIsNotNullOrderByCreatedAtDesc(ReplyStatus.AUTO_PUBLISHED)
                .stream()
                .map(ReplyCandidateResponse::from)
                .toList();
        return List.copyOf(newestFirst.reversed());
    }

    private ReentrantLock messageLockFor(ReplyRequest request) {
        int hash = java.util.Objects.hash(request.platform(), request.roomId(), request.messageId());
        return messageLocks[Math.floorMod(hash, messageLocks.length)];
    }

    private ReplyCandidateResponse findExisting(ReplyRequest request) {
        return repository.findByPlatformAndRoomIdAndMessageId(
                        request.platform(),
                        request.roomId(),
                        request.messageId()
                )
                .map(ReplyCandidateResponse::from)
                .orElse(null);
    }

    private SavedReply saveOrFindExisting(ReplyRequest request, ReplyCandidate candidate) {
        try {
            return new SavedReply(repository.saveAndFlush(candidate), true);
        } catch (DataIntegrityViolationException duplicateMessage) {
            return repository.findByPlatformAndRoomIdAndMessageId(
                            request.platform(),
                            request.roomId(),
                            request.messageId()
                    )
                    .map(existing -> new SavedReply(existing, false))
                    .orElseThrow(() -> duplicateMessage);
        }
    }

    private ReplyCandidate createReplyCandidate(ReplyRequest request, LiveHostProfileSnapshot profile) {
        if (runtimeControlService.isPaused()) {
            return ReplyCandidate.paused(request, PAUSED_REASON);
        }
        try {
            ReplyAdmissionDecision admission = replyAdmissionService.evaluate(request);
            if (!admission.allowed()) {
                return ReplyCandidate.rateLimited(request, admission.reason());
            }
        } catch (RuntimeException exception) {
            logger.warn(
                    "Reply admission failed for room {}: {}",
                    request.roomId(),
                    exception.getClass().getSimpleName()
            );
            return ReplyCandidate.processingFailed(request, PROCESSING_UNAVAILABLE_REASON);
        }
        overlayPublisher.replyStarted(request.messageId(), request.senderName(), request.messageText());
        boolean replyWillBePublished = false;
        try {
            LiveHostReply reply = liveHostConversationService.replyStreaming(request, profile);
            if (reply == null || reply.overlayText() == null) {
                logger.warn("Model returned no displayable reply for room {}; marking reply as MODEL_FAILED", request.roomId());
                return ReplyCandidate.modelFailed(request, EMPTY_MODEL_RESPONSE_REASON);
            }
            ReplyCandidate candidate = ReplyCandidate.autoPublished(request, reply, profile.version());
            replyWillBePublished = true;
            return candidate;
        } catch (OutputModerationException exception) {
            logger.warn("Model reply was blocked by output moderation for room {}", request.roomId());
            return ReplyCandidate.blocked(request, exception.getMessage());
        } catch (ReplyPausedException exception) {
            logger.info("Model reply was discarded because automatic replies were paused for room {}", request.roomId());
            return ReplyCandidate.paused(request, PAUSED_REASON);
        } catch (RuntimeException exception) {
            logger.warn("Model reply generation failed for room {}", request.roomId(), exception);
            return ReplyCandidate.modelFailed(request, MODEL_UNAVAILABLE_REASON);
        } finally {
            if (!replyWillBePublished) {
                overlayPublisher.replyFinished(request.messageId());
            }
        }
    }

    private record SavedReply(ReplyCandidate candidate, boolean created) {
    }

}
