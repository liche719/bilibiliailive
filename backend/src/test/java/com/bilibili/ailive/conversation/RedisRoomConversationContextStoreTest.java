package com.bilibili.ailive.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRoomConversationContextStoreTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private ZSetOperations<String, String> zSetOperations;
    private ObjectMapper objectMapper;
    private RedisRoomConversationContextStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        zSetOperations = mock(ZSetOperations.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        store = new RedisRoomConversationContextStore(
                redisTemplate,
                objectMapper,
                new RoomContextProperties(12, TTL, "ai-live:room-context"),
                new LiveReplyMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    void atomicallyUpsertsViewerAndHostReplyIntoTheBoundedWindow() {
        ReplyRequest request = request("message-id", "小明", "鸡蛋对身体健康");

        store.observe(request);
        store.attachHostReply(request, "适量吃鸡蛋通常没问题。");

        verify(redisTemplate, times(2)).execute(
                any(RedisScript.class),
                anyList(),
                eq("message-id"),
                anyString(),
                anyString(),
                eq("12"),
                eq(Long.toString(TTL.toMillis()))
        );
    }

    @Test
    void formatsChronologicalMessagesFromTheOrderedWindow() throws Exception {
        String first = objectMapper.writeValueAsString(new RoomConversationEntry(
                "message-1", "小明", "鸡蛋对身体健康", "适量食用通常有益。",
                Instant.parse("2026-08-10T10:00:00Z")));
        String second = objectMapper.writeValueAsString(new RoomConversationEntry(
                "message-2", "小红", "那刚刚那个人说得对吗？", null,
                Instant.parse("2026-08-10T10:00:01Z")));
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(new LinkedHashSet<>(List.of("message-1", "message-2")));
        when(hashOperations.multiGet(anyString(), anyList())).thenReturn(List.of(first, second));

        String context = store.recentContext("BILIBILI:1000:viewer-id");

        assertTrue(context.contains("小明：鸡蛋对身体健康"));
        assertTrue(context.contains("AI 主播回复 小明：适量食用通常有益。"));
        assertTrue(context.contains("小红：那刚刚那个人说得对吗？"));
        assertTrue(context.indexOf("小明：鸡蛋对身体健康") < context.indexOf("小红：那刚刚那个人说得对吗？"));
    }

    private static ReplyRequest request(String messageId, String senderName, String message) {
        return new ReplyRequest(
                "BILIBILI", "1000", senderName + "-id", senderName,
                messageId, message, Instant.parse("2026-08-10T10:00:02Z")
        );
    }
}
