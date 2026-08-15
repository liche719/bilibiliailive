package com.bilibili.ailive.liveplatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

@Component
class RedisLiveAudienceTracker implements LiveAudienceTracker {

    private static final Logger logger = LoggerFactory.getLogger(RedisLiveAudienceTracker.class);

    private final StringRedisTemplate redisTemplate;
    private final LiveAudienceProperties properties;
    private final Clock clock;

    @Autowired
    RedisLiveAudienceTracker(StringRedisTemplate redisTemplate, LiveAudienceProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC());
    }

    RedisLiveAudienceTracker(
            StringRedisTemplate redisTemplate,
            LiveAudienceProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void observe(LiveAudienceActivity activity, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String activeKey = key(activity.platform(), activity.roomId(), sessionId, "active");
            String observedKey = key(activity.platform(), activity.roomId(), sessionId, "observed");
            redisTemplate.opsForZSet().add(activeKey, activity.viewerId(), activity.occurredAt().toEpochMilli());
            redisTemplate.opsForSet().add(observedKey, activity.viewerId());
            redisTemplate.expire(activeKey, properties.sessionTtl());
            redisTemplate.expire(observedKey, properties.sessionTtl());
        } catch (RuntimeException exception) {
            logger.warn("Unable to record live audience activity: {}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public LiveAudienceSnapshot snapshot(LivePlatform platform, String roomId, String sessionId) {
        if (roomId == null || roomId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return LiveAudienceSnapshot.unavailable();
        }
        try {
            String activeKey = key(platform, roomId, sessionId, "active");
            long cutoff = clock.instant().minus(properties.activeWindow()).toEpochMilli();
            redisTemplate.opsForZSet().removeRangeByScore(activeKey, Double.NEGATIVE_INFINITY, cutoff);
            Long active = redisTemplate.opsForZSet().zCard(activeKey);
            Long observed = redisTemplate.opsForSet().size(key(platform, roomId, sessionId, "observed"));
            return new LiveAudienceSnapshot(value(active), value(observed), true);
        } catch (RuntimeException exception) {
            logger.warn("Unable to read live audience snapshot: {}", exception.getClass().getSimpleName());
            return LiveAudienceSnapshot.unavailable();
        }
    }

    private String key(LivePlatform platform, String roomId, String sessionId, String scope) {
        String identity = platform.name() + "\u001f" + roomId + "\u001f" + sessionId;
        return properties.redisKeyPrefix() + ":" + scope + ":" + sha256(identity);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
