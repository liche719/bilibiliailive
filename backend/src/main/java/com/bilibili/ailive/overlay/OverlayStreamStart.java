package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;

import java.util.UUID;

public record OverlayStreamStart(
        UUID candidateId,
        String messageId,
        String senderName,
        String sourceText,
        String audioUrl,
        int volume
) {

    static OverlayStreamStart from(ReplyCandidateResponse candidate, String audioUrl, int volume) {
        return new OverlayStreamStart(
                candidate.id(),
                candidate.messageId(),
                candidate.senderName(),
                candidate.sourceText(),
                audioUrl,
                volume
        );
    }
}
