package com.bilibili.ailive.liveplatform.bilibili;

import com.bilibili.ailive.liveplatform.LiveChatEvent;
import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliMessageParserTest {

    @Test
    void convertsTheOfficialDanmakuCommandIntoAStandardLiveEvent() {
        BilibiliMessageParser parser = new BilibiliMessageParser(new ObjectMapper());
        BilibiliOpenLiveSession session = new BilibiliOpenLiveSession(
                "game-id",
                "{}",
                List.of("wss://example.com"),
                1000L,
                "anchor-open-id"
        );
        String json = """
                {
                  "cmd":"LIVE_OPEN_PLATFORM_DM",
                  "data":{
                    "room_id":1000,
                    "open_id":"viewer-open-id",
                    "uname":"小明",
                    "msg":"你好",
                    "msg_id":"message-1",
                    "timestamp":1786291200
                  }
                }
                """;

        LiveChatEvent event = parser.parse(json.getBytes(StandardCharsets.UTF_8), session).orElseThrow();

        assertEquals("1000", event.roomId());
        assertEquals("viewer-open-id", event.senderId());
        assertEquals("小明", event.senderName());
        assertEquals("message-1", event.messageId());
        assertEquals("你好", event.messageText());
        assertEquals(Instant.ofEpochSecond(1786291200), event.occurredAt());
        assertFalse(event.broadcasterMessage());
    }

    @Test
    void marksMessagesFromTheAnchorAndIgnoresOtherCommands() {
        BilibiliMessageParser parser = new BilibiliMessageParser(new ObjectMapper());
        BilibiliOpenLiveSession session = new BilibiliOpenLiveSession(
                "game-id", "{}", List.of("wss://example.com"), 1000L, "anchor-open-id"
        );
        String anchorMessage = """
                {"cmd":"LIVE_OPEN_PLATFORM_DM","data":{"room_id":1000,"open_id":"anchor-open-id","msg":"测试","msg_id":"message-2","timestamp":1786291200}}
                """;

        assertTrue(parser.parse(anchorMessage.getBytes(StandardCharsets.UTF_8), session).orElseThrow().broadcasterMessage());
        assertTrue(parser.parse("{\"cmd\":\"LIVE_OPEN_PLATFORM_LIKE\"}".getBytes(StandardCharsets.UTF_8), session).isEmpty());
    }

    @Test
    void extractsAudienceActivityFromOfficialRoomEnterAndDanmakuEvents() {
        BilibiliMessageParser parser = new BilibiliMessageParser(new ObjectMapper());
        String roomEnter = """
                {"cmd":"LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER","data":{"room_id":1000,"open_id":"viewer-1","timestamp":1786291200}}
                """;
        String danmaku = """
                {"cmd":"LIVE_OPEN_PLATFORM_DM","data":{"room_id":1000,"open_id":"viewer-2","timestamp":1786291201}}
                """;

        assertEquals("viewer-1", parser.parseAudienceActivity(roomEnter.getBytes(StandardCharsets.UTF_8)).orElseThrow().viewerId());
        assertEquals("viewer-2", parser.parseAudienceActivity(danmaku.getBytes(StandardCharsets.UTF_8)).orElseThrow().viewerId());
        assertTrue(parser.parseAudienceActivity("{\"cmd\":\"LIVE_OPEN_PLATFORM_LIKE\"}".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void extractsNamedViewerEntryFromTheOfficialRoomEnterEvent() {
        BilibiliMessageParser parser = new BilibiliMessageParser(new ObjectMapper());
        String roomEnter = """
                {"cmd":"LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER","data":{"room_id":1000,"open_id":"viewer-1","uname":"新来的小纸船","timestamp":1786291200}}
                """;

        ViewerEnteredEvent event = parser.parseViewerEntered(roomEnter.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertEquals("1000", event.roomId());
        assertEquals("viewer-1", event.viewerId());
        assertEquals("新来的小纸船", event.viewerName());
        assertEquals(Instant.ofEpochSecond(1786291200), event.occurredAt());
    }
}
