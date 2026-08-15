package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.LivePlatform;
import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisViewerWelcomeAdmissionTest {

    @Test
    @SuppressWarnings("unchecked")
    void admitsOnlyTheFirstEntryForAViewerInTheSession() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(12))))
                .thenReturn(true, false);
        RedisViewerWelcomeAdmission admission = new RedisViewerWelcomeAdmission(redisTemplate, properties());
        ViewerEnteredEvent event = new ViewerEnteredEvent(
                LivePlatform.BILIBILI,
                "1000",
                "viewer-1",
                "小纸船",
                Instant.parse("2026-08-12T00:00:00Z")
        );

        assertTrue(admission.admit(event, "game-1"));
        assertFalse(admission.admit(event, "game-1"));
        verify(values, org.mockito.Mockito.times(2))
                .setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(12)));
    }

    private static ViewerWelcomeProperties properties() {
        return new ViewerWelcomeProperties(
                true,
                Duration.ofSeconds(6),
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
                Duration.ofHours(12),
                Duration.ofSeconds(5),
                3,
                20,
                "test:welcome"
        );
    }
}
