package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;

public interface OverlayPublisher {

    default void replyReceived(String messageId, String senderName, String sourceText) {
    }

    default void replyStarted(String messageId, String senderName, String sourceText) {
    }

    default void replyFinished(String messageId) {
    }

    default void replyOutcome(ReplyCandidateResponse candidate) {
    }

    default void welcome(OverlayWelcome welcome) {
    }

    default boolean isReplyActive() {
        return false;
    }

    void publish(ReplyCandidateResponse candidate);
}
