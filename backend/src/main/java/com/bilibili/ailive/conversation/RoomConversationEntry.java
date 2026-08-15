package com.bilibili.ailive.conversation;

import java.time.Instant;

record RoomConversationEntry(
        String messageId,
        String viewerName,
        String viewerMessage,
        String hostReply,
        Instant occurredAt
) {
}
