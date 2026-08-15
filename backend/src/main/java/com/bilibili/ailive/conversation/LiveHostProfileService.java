package com.bilibili.ailive.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;

@Service
class LiveHostProfileService {

    private final LiveHostProfileRepository repository;
    private final Clock clock;

    @Autowired
    LiveHostProfileService(LiveHostProfileRepository repository) {
        this(repository, Clock.systemUTC());
    }

    LiveHostProfileService(LiveHostProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    LiveHostProfileSnapshot resolve(String roomId) {
        return repository.findById(roomId)
                .map(LiveHostProfile::snapshot)
                .orElseGet(() -> LiveHostProfileSnapshot.defaults(roomId));
    }

    @Transactional
    LiveHostProfileSnapshot save(String roomId, LiveHostProfileRequest request) {
        LiveHostProfile profile = repository.findById(roomId).map(existing -> {
            existing.update(request, existing.snapshot().version() + 1, clock.instant());
            return existing;
        }).orElseGet(() -> LiveHostProfile.create(roomId, request, 1, clock.instant()));
        return repository.saveAndFlush(profile).snapshot();
    }
}
