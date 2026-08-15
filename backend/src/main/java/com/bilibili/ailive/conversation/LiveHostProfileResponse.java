package com.bilibili.ailive.conversation;

import java.time.Instant;

public record LiveHostProfileResponse(
        String roomId,
        String hostName,
        String persona,
        String liveTopic,
        String replyStyle,
        int maxReplyCharacters,
        String forbiddenTopics,
        String welcomeMessage,
        boolean proactiveQuestions,
        long version,
        Instant updatedAt,
        boolean persisted
) {
    static LiveHostProfileResponse from(LiveHostProfileSnapshot snapshot) {
        return new LiveHostProfileResponse(
                snapshot.roomId(),
                snapshot.hostName(),
                snapshot.persona(),
                snapshot.liveTopic(),
                snapshot.replyStyle(),
                snapshot.maxReplyCharacters(),
                snapshot.forbiddenTopics(),
                snapshot.welcomeMessage(),
                snapshot.proactiveQuestions(),
                snapshot.version(),
                snapshot.updatedAt(),
                snapshot.version() > 0
        );
    }
}
