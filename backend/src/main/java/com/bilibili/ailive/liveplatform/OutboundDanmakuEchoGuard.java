package com.bilibili.ailive.liveplatform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class OutboundDanmakuEchoGuard {

    private final DanmakuProperties properties;
    private final Clock clock;
    private final Map<String, Instant> knownMessageIds = new HashMap<>();
    private final Map<String, Instant> knownTextHashes = new HashMap<>();

    @Autowired
    OutboundDanmakuEchoGuard(DanmakuProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OutboundDanmakuEchoGuard(DanmakuProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized void record(
            LivePlatform platform,
            String roomId,
            String text,
            String platformMessageId
    ) {
        Instant expiresAt = clock.instant().plus(properties.echoRetention());
        pruneExpired();
        if (platformMessageId != null && !platformMessageId.isBlank()) {
            knownMessageIds.put(messageKey(platform, roomId, platformMessageId), expiresAt);
        }
        knownTextHashes.put(textKey(platform, roomId, text), expiresAt);
    }

    public synchronized boolean isEcho(LiveChatEvent event) {
        if (!event.broadcasterMessage()) {
            return false;
        }
        pruneExpired();
        return knownMessageIds.containsKey(messageKey(event.platform(), event.roomId(), event.messageId()))
                || knownTextHashes.containsKey(textKey(event.platform(), event.roomId(), event.messageText()));
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        removeExpired(knownMessageIds, now);
        removeExpired(knownTextHashes, now);
    }

    private static void removeExpired(Map<String, Instant> values, Instant now) {
        Iterator<Instant> iterator = values.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().isAfter(now)) {
                iterator.remove();
            }
        }
    }

    private static String messageKey(LivePlatform platform, String roomId, String messageId) {
        return platform.name() + ':' + roomId + ':' + messageId;
    }

    private static String textKey(LivePlatform platform, String roomId, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.trim().getBytes(StandardCharsets.UTF_8));
            return platform.name() + ':' + roomId + ':' + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
