package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.DanmakuDeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record ReplyCandidateResponse(
        UUID id,
        String platform,
        String roomId,
        String senderId,
        String senderName,
        String messageId,
        String sourceText,
        String candidateText,
        String danmakuText,
        DanmakuDeliveryStatus danmakuStatus,
        String danmakuPlatformMessageId,
        String danmakuDecisionReason,
        ReplyStatus status,
        String decisionReason,
        Long promptProfileVersion,
        Instant occurredAt,
        Instant createdAt
) {
    static ReplyCandidateResponse from(ReplyCandidate candidate) {
        return new ReplyCandidateResponse(
                candidate.getId(),
                candidate.getPlatform(),
                candidate.getRoomId(),
                candidate.getSenderId(),
                candidate.getSenderName(),
                candidate.getMessageId(),
                candidate.getSourceText(),
                candidate.getCandidateText(),
                candidate.getDanmakuText(),
                candidate.getDanmakuStatus(),
                candidate.getDanmakuPlatformMessageId(),
                candidate.getDanmakuDecisionReason(),
                candidate.getStatus(),
                candidate.getDecisionReason(),
                candidate.getPromptProfileVersion(),
                candidate.getOccurredAt(),
                candidate.getCreatedAt()
        );
    }
}
