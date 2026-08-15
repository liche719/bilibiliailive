package com.bilibili.ailive.conversation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
class ReplyRetentionService {

    private final ReplyCandidateRepository repository;
    private final LiveDataRetentionProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    ReplyRetentionService(
            ReplyCandidateRepository repository,
            LiveDataRetentionProperties properties,
            MeterRegistry meterRegistry
    ) {
        this(repository, properties, meterRegistry, Clock.systemUTC());
    }

    ReplyRetentionService(
            ReplyCandidateRepository repository,
            LiveDataRetentionProperties properties,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.data-retention.cleanup-interval:PT1H}")
    @Transactional
    void deleteExpiredReplies() {
        Instant cutoff = clock.instant().minus(properties.replyHistory());
        long deleted = repository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            meterRegistry.counter("ai.live.reply.retention.deleted").increment(deleted);
        }
    }
}
