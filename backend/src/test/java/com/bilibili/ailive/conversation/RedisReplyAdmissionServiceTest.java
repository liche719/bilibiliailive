package com.bilibili.ailive.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class RedisReplyAdmissionServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisReplyAdmissionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RedisReplyAdmissionService(
                redisTemplate,
                properties(),
                mock(LiveReplyMetrics.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void admitsWithinBothLimitsAndUsesOneOpaqueGlobalCounter() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);

        ReplyAdmissionDecision decision = service.evaluate(request());
        ReplyAdmissionDecision anotherRoomDecision = service.evaluate(request("another-room", "another-viewer"));

        assertTrue(decision.allowed());
        assertTrue(anotherRoomDecision.allowed());
        ArgumentCaptor<String> userKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(userKey.capture(), anyString(), any(Duration.class));
        assertFalse(userKey.getAllValues().getFirst().contains("viewer-secret"));
        ArgumentCaptor<List<String>> modelCallKeys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2))
                .execute(any(DefaultRedisScript.class), modelCallKeys.capture(), anyString());
        assertFalse(modelCallKeys.getAllValues().getFirst().getFirst().contains("room-secret"));
        assertEquals(1, modelCallKeys.getAllValues().stream().distinct().count());
    }

    @Test
    void rejectsDuringTheUserCooldownWithoutConsumingTheGlobalWindow() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        ReplyAdmissionDecision decision = service.evaluate(request());

        assertFalse(decision.allowed());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsWhenTheAtomicGlobalCounterExceedsTheConfiguredLimit() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(21L);

        assertFalse(service.evaluate(request()).allowed());
    }

    private static LiveReplyPolicyProperties properties() {
        return new LiveReplyPolicyProperties(
                160,
                Duration.ofSeconds(3),
                20,
                Duration.ofMinutes(1),
                4,
                2,
                100,
                Duration.ofSeconds(5),
                4,
                "ai-live:reply-policy"
        );
    }

    private static ReplyRequest request() {
        return request("room-secret", "viewer-secret");
    }

    private static ReplyRequest request(String roomId, String senderId) {
        return new ReplyRequest(
                "BILIBILI",
                roomId,
                senderId,
                "message-1",
                "你好",
                Instant.parse("2026-08-10T00:00:00Z")
        );
    }
}
