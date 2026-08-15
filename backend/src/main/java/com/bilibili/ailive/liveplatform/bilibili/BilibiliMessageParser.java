package com.bilibili.ailive.liveplatform.bilibili;

import com.bilibili.ailive.liveplatform.LiveChatEvent;
import com.bilibili.ailive.liveplatform.LiveAudienceActivity;
import com.bilibili.ailive.liveplatform.LivePlatform;
import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Component
class BilibiliMessageParser {

    private static final String DANMAKU_COMMAND = "LIVE_OPEN_PLATFORM_DM";
    private static final String ROOM_ENTER_COMMAND = "LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER";

    private final ObjectMapper objectMapper;

    BilibiliMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Optional<LiveChatEvent> parse(byte[] body, BilibiliOpenLiveSession session) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!DANMAKU_COMMAND.equals(root.path("cmd").asText())) {
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            String senderId = text(data, "open_id");
            String senderName = text(data, "uname");
            String messageId = text(data, "msg_id");
            String message = text(data, "msg");
            long roomId = data.path("room_id").asLong();
            long timestamp = data.path("timestamp").asLong();
            if (senderId == null || messageId == null || message == null || roomId < 1 || timestamp < 1) {
                return Optional.empty();
            }
            return Optional.of(new LiveChatEvent(
                    LivePlatform.BILIBILI,
                    Long.toString(roomId),
                    senderId,
                    senderName == null ? "观众" : senderName,
                    messageId,
                    message,
                    Instant.ofEpochSecond(timestamp),
                    senderId.equals(session.anchorOpenId())
            ));
        } catch (IOException exception) {
            throw new BilibiliOpenLiveException("Unable to parse Bilibili live message", exception);
        }
    }

    Optional<LiveAudienceActivity> parseAudienceActivity(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String command = root.path("cmd").asText();
            if (!DANMAKU_COMMAND.equals(command) && !ROOM_ENTER_COMMAND.equals(command)) {
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            String viewerId = text(data, "open_id");
            long roomId = data.path("room_id").asLong();
            long timestamp = data.path("timestamp").asLong();
            if (viewerId == null || roomId < 1 || timestamp < 1) {
                return Optional.empty();
            }
            return Optional.of(new LiveAudienceActivity(
                    LivePlatform.BILIBILI,
                    Long.toString(roomId),
                    viewerId,
                    Instant.ofEpochSecond(timestamp)
            ));
        } catch (IOException exception) {
            throw new BilibiliOpenLiveException("Unable to parse Bilibili audience activity", exception);
        }
    }

    Optional<ViewerEnteredEvent> parseViewerEntered(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!ROOM_ENTER_COMMAND.equals(root.path("cmd").asText())) {
                return Optional.empty();
            }
            JsonNode data = root.path("data");
            String viewerId = text(data, "open_id");
            String viewerName = text(data, "uname");
            long roomId = data.path("room_id").asLong();
            long timestamp = data.path("timestamp").asLong();
            if (viewerId == null || roomId < 1 || timestamp < 1) {
                return Optional.empty();
            }
            return Optional.of(new ViewerEnteredEvent(
                    LivePlatform.BILIBILI,
                    Long.toString(roomId),
                    viewerId,
                    viewerName,
                    Instant.ofEpochSecond(timestamp)
            ));
        } catch (IOException exception) {
            throw new BilibiliOpenLiveException("Unable to parse Bilibili viewer entry", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
