package com.bilibili.ailive.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "live_host_profiles")
class LiveHostProfile {

    @Id
    @Column(length = 64)
    private String roomId;

    @Column(nullable = false, length = 80)
    private String hostName;

    @Column(nullable = false, length = 1000)
    private String persona;

    @Column(nullable = false, length = 500)
    private String liveTopic;

    @Column(nullable = false, length = 500)
    private String replyStyle;

    @Column(nullable = false)
    private int maxReplyCharacters;

    @Column(nullable = false, length = 1000)
    private String forbiddenTopics;

    @Column(nullable = false, length = 500)
    private String welcomeMessage;

    @Column(nullable = false)
    private boolean proactiveQuestions;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private Instant updatedAt;

    protected LiveHostProfile() {
    }

    static LiveHostProfile create(String roomId, LiveHostProfileRequest request, long version, Instant updatedAt) {
        LiveHostProfile profile = new LiveHostProfile();
        profile.roomId = roomId;
        profile.update(request, version, updatedAt);
        return profile;
    }

    void update(LiveHostProfileRequest request, long version, Instant updatedAt) {
        this.hostName = request.hostName().trim();
        this.persona = request.persona().trim();
        this.liveTopic = request.liveTopic().trim();
        this.replyStyle = request.replyStyle().trim();
        this.maxReplyCharacters = request.maxReplyCharacters();
        this.forbiddenTopics = request.forbiddenTopics().trim();
        this.welcomeMessage = request.welcomeMessage().trim();
        this.proactiveQuestions = request.proactiveQuestions();
        this.version = version;
        this.updatedAt = updatedAt;
    }

    LiveHostProfileSnapshot snapshot() {
        return new LiveHostProfileSnapshot(
                roomId,
                hostName,
                persona,
                liveTopic,
                replyStyle,
                maxReplyCharacters,
                forbiddenTopics,
                welcomeMessage,
                proactiveQuestions,
                version,
                updatedAt
        );
    }
}
