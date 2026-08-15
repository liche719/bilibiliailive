package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import com.bilibili.ailive.liveplatform.DanmakuDispatchResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reply_candidates")
public class ReplyCandidate {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String roomId;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(nullable = false, length = 128)
    private String senderId;

    @Column(nullable = false, length = 128)
    private String senderName;

    @Column(nullable = false, length = 128)
    private String messageId;

    @Column(nullable = false, length = 512)
    private String sourceText;

    @Column(length = 512)
    private String candidateText;

    @Column(length = 160)
    private String danmakuText;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private DanmakuDeliveryStatus danmakuStatus;

    @Column(length = 128)
    private String danmakuPlatformMessageId;

    @Column(length = 256)
    private String danmakuDecisionReason;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private ReplyStatus status;

    @Column(length = 256)
    private String decisionReason;

    private Long promptProfileVersion;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant occurredAt;

    protected ReplyCandidate() {
    }

    private ReplyCandidate(
            ReplyRequest request,
            String candidateText,
            String danmakuText,
            DanmakuDeliveryStatus danmakuStatus,
            ReplyStatus status,
            String decisionReason
    ) {
        this.id = UUID.randomUUID();
        this.platform = request.platform();
        this.roomId = request.roomId();
        this.senderId = request.senderId();
        this.senderName = request.senderName();
        this.messageId = request.messageId();
        this.sourceText = request.messageText();
        this.candidateText = candidateText;
        this.danmakuText = danmakuText;
        this.danmakuStatus = danmakuStatus;
        this.status = status;
        this.decisionReason = decisionReason;
        this.createdAt = Instant.now();
        this.occurredAt = request.occurredAt();
    }

    public static ReplyCandidate autoPublished(
            ReplyRequest request,
            LiveHostReply reply,
            long promptProfileVersion
    ) {
        DanmakuDeliveryStatus deliveryStatus = reply.sendDanmaku()
                ? DanmakuDeliveryStatus.PENDING
                : DanmakuDeliveryStatus.NOT_REQUESTED;
        ReplyCandidate candidate = new ReplyCandidate(
                request,
                reply.overlayText(),
                reply.danmakuText(),
                deliveryStatus,
                ReplyStatus.AUTO_PUBLISHED,
                null
        );
        candidate.promptProfileVersion = promptProfileVersion;
        return candidate;
    }

    public static ReplyCandidate blocked(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.BLOCKED, reason);
    }

    public static ReplyCandidate modelFailed(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.MODEL_FAILED, reason);
    }

    public static ReplyCandidate rateLimited(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.RATE_LIMITED, reason);
    }

    public static ReplyCandidate paused(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.PAUSED, reason);
    }

    public static ReplyCandidate overloaded(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.OVERLOADED, reason);
    }

    public static ReplyCandidate processingFailed(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.PROCESSING_FAILED, reason);
    }

    public static ReplyCandidate echoIgnored(ReplyRequest request, String reason) {
        return rejected(request, ReplyStatus.ECHO_IGNORED, reason);
    }

    private static ReplyCandidate rejected(ReplyRequest request, ReplyStatus status, String reason) {
        return new ReplyCandidate(
                request,
                null,
                null,
                DanmakuDeliveryStatus.NOT_REQUESTED,
                status,
                reason
        );
    }

    public void recordDanmakuResult(DanmakuDispatchResult result) {
        this.danmakuStatus = result.status();
        this.danmakuPlatformMessageId = result.platformMessageId();
        this.danmakuDecisionReason = result.reason();
    }

    public String getPlatform() {
        return platform;
    }

    public UUID getId() {
        return id;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSourceText() {
        return sourceText;
    }

    public String getCandidateText() {
        return candidateText;
    }

    public ReplyStatus getStatus() {
        return status;
    }

    public String getDanmakuText() {
        return danmakuText;
    }

    public DanmakuDeliveryStatus getDanmakuStatus() {
        return danmakuStatus;
    }

    public String getDanmakuPlatformMessageId() {
        return danmakuPlatformMessageId;
    }

    public String getDanmakuDecisionReason() {
        return danmakuDecisionReason;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getPromptProfileVersion() {
        return promptProfileVersion;
    }
}
