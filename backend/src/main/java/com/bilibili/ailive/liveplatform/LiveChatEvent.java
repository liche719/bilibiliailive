package com.bilibili.ailive.liveplatform;

import java.time.Instant;

public record LiveChatEvent(
        LivePlatform platform,
        String roomId,
        String senderId,
        String senderName,
        String messageId,
        String messageText,
        Instant occurredAt,
        boolean broadcasterMessage
) {

    public LiveChatEvent(
            LivePlatform platform,
            String roomId,
            String senderId,
            String messageId,
            String messageText,
            Instant occurredAt
    ) {
        this(platform, roomId, senderId, senderId, messageId, messageText, occurredAt, false);
    }

    public LiveChatEvent(
            LivePlatform platform,
            String roomId,
            String senderId,
            String messageId,
            String messageText,
            Instant occurredAt,
            boolean broadcasterMessage
    ) {
        this(platform, roomId, senderId, senderId, messageId, messageText, occurredAt, broadcasterMessage);
    }

    public LiveChatEvent {
        if (platform == null || isBlank(roomId) || isBlank(senderId) || isBlank(senderName) || isBlank(messageId) || isBlank(messageText) || occurredAt == null) {
            throw new IllegalArgumentException("A live chat event requires platform, room, sender, sender name, message ID, text and timestamp");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
