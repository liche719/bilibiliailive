package com.bilibili.ailive.conversation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveHostProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void returnsSafeDefaultsForAnUnconfiguredRoom() {
        LiveHostProfileRepository repository = mock(LiveHostProfileRepository.class);
        when(repository.findById("1000")).thenReturn(Optional.empty());
        LiveHostProfileService service = service(repository);

        LiveHostProfileSnapshot profile = service.resolve("1000");

        assertEquals(0, profile.version());
        assertEquals("AI 主播", profile.hostName());
        assertFalse(profile.proactiveQuestions());
    }

    @Test
    void createsAndThenVersionsRoomProfiles() {
        LiveHostProfileRepository repository = mock(LiveHostProfileRepository.class);
        LiveHostProfileRequest request = request("小航");
        when(repository.findById("1000"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(LiveHostProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LiveHostProfileService service = service(repository);

        LiveHostProfileSnapshot created = service.save("1000", request);

        assertEquals(1, created.version());
        assertEquals("小航", created.hostName());
        assertEquals(NOW, created.updatedAt());
        assertTrue(created.systemPromptSection().contains("科技闲聊"));

        LiveHostProfile existing = LiveHostProfile.create("1000", request, 3, NOW.minusSeconds(60));
        when(repository.findById("1000")).thenReturn(Optional.of(existing));

        LiveHostProfileSnapshot updated = service.save("1000", request("小航二号"));

        assertEquals(4, updated.version());
        assertEquals("小航二号", updated.hostName());
    }

    @Test
    void matchesConfiguredForbiddenTopicsAsDelimitedKeywords() {
        LiveHostProfileSnapshot profile = new LiveHostProfileSnapshot(
                "1000",
                "小航",
                "科技主播",
                "",
                "自然",
                120,
                "博彩，危险挑战;剧透",
                "欢迎",
                false,
                1,
                NOW
        );

        assertTrue(profile.containsForbiddenTopic("能介绍一下危险挑战吗"));
        assertTrue(profile.containsForbiddenTopic("不要剧透结局"));
        assertFalse(profile.containsForbiddenTopic("今天聊 Java"));
    }

    private static LiveHostProfileService service(LiveHostProfileRepository repository) {
        return new LiveHostProfileService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static LiveHostProfileRequest request(String hostName) {
        return new LiveHostProfileRequest(
                hostName,
                "热情但不夸张的科技主播",
                "科技闲聊",
                "自然、简洁",
                120,
                "违法危险行为",
                "欢迎来到直播间",
                true
        );
    }
}
