package com.bilibili.ailive.conversation;

import java.time.Instant;

public record ReplyRequest(
        String platform,
        String roomId,
        String senderId,
        String senderName,
        String messageId,
        String messageText,
        Instant occurredAt
) {

    public ReplyRequest(
            String platform,
            String roomId,
            String senderId,
            String messageId,
            String messageText,
            Instant occurredAt
    ) {
        this(platform, roomId, senderId, senderId, messageId, messageText, occurredAt);
    }

    public ReplyRequest {
        if (isBlank(platform)
                || isBlank(roomId)
                || isBlank(senderId)
                || isBlank(senderName)
                || isBlank(messageId)
                || isBlank(messageText)
                || occurredAt == null) {
            throw new IllegalArgumentException("A reply request requires platform, room, sender, sender name, message ID, text and timestamp");
        }
    }

    public String memoryId() {
        return platform + ":" + roomId + ":" + senderId;
    }

    public String roomExecutionKey() {
        return platform + ":" + roomId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
