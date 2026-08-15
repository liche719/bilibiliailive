package com.bilibili.ailive.liveplatform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLiveAudienceTrackerTest {

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zSetOperations;
    private SetOperations<String, String> setOperations;
    private RedisLiveAudienceTracker tracker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        tracker = new RedisLiveAudienceTracker(
                redisTemplate,
                new LiveAudienceProperties(Duration.ofMinutes(5), Duration.ofHours(12), "ai-live:audience"),
                Clock.fixed(Instant.parse("2026-08-10T10:05:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void recordsUniqueSessionViewersAndRecentActivity() {
        LiveAudienceActivity activity = new LiveAudienceActivity(
                LivePlatform.BILIBILI, "1000", "viewer-1", Instant.parse("2026-08-10T10:04:00Z"));

        tracker.observe(activity, "game-1");

        verify(zSetOperations).add(anyString(), org.mockito.ArgumentMatchers.eq("viewer-1"),
                org.mockito.ArgumentMatchers.eq(activity.occurredAt().toEpochMilli() * 1.0));
        verify(setOperations).add(anyString(), org.mockito.ArgumentMatchers.eq("viewer-1"));
    }

    @Test
    void reportsFiveMinuteActivityAndObservedSessionTotals() {
        when(zSetOperations.zCard(anyString())).thenReturn(3L);
        when(setOperations.size(anyString())).thenReturn(8L);

        LiveAudienceSnapshot snapshot = tracker.snapshot(LivePlatform.BILIBILI, "1000", "game-1");

        assertEquals(3, snapshot.recentlyActiveViewers());
        assertEquals(8, snapshot.observedSessionViewers());
        verify(zSetOperations).removeRangeByScore(
                anyString(),
                org.mockito.ArgumentMatchers.eq(Double.NEGATIVE_INFINITY),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-10T10:00:00Z").toEpochMilli() * 1.0)
        );
    }
}
