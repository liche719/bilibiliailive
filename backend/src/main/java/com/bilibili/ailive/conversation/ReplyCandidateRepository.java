package com.bilibili.ailive.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReplyCandidateRepository extends JpaRepository<ReplyCandidate, UUID> {

    List<ReplyCandidate> findTop100ByOrderByCreatedAtDesc();

    Optional<ReplyCandidate> findByPlatformAndRoomIdAndMessageId(String platform, String roomId, String messageId);

    Optional<ReplyCandidate> findFirstByStatusAndCandidateTextIsNotNullOrderByCreatedAtDesc(ReplyStatus status);

    List<ReplyCandidate> findTop6ByStatusAndCandidateTextIsNotNullOrderByCreatedAtDesc(ReplyStatus status);

    long deleteByCreatedAtBefore(java.time.Instant cutoff);
}
