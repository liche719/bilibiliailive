package com.bilibili.ailive.conversation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplyRetentionServiceTest {

    @Test
    void deletesOnlyRepliesOlderThanTheConfiguredRetention() {
        ReplyCandidateRepository repository = mock(ReplyCandidateRepository.class);
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        Instant cutoff = Instant.parse("2026-08-03T00:00:00Z");
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(3L);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ReplyRetentionService service = new ReplyRetentionService(
                repository,
                new LiveDataRetentionProperties(Duration.ofDays(7), Duration.ofHours(1)),
                meterRegistry,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.deleteExpiredReplies();

        verify(repository).deleteByCreatedAtBefore(cutoff);
        org.junit.jupiter.api.Assertions.assertEquals(
                3.0,
                meterRegistry.counter("ai.live.reply.retention.deleted").count()
        );
    }
}
