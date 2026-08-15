package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
class RedisViewerWelcomeAdmission implements ViewerWelcomeAdmission {

    private static final Logger logger = LoggerFactory.getLogger(RedisViewerWelcomeAdmission.class);

    private final StringRedisTemplate redisTemplate;
    private final ViewerWelcomeProperties properties;

    RedisViewerWelcomeAdmission(StringRedisTemplate redisTemplate, ViewerWelcomeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean admit(ViewerEnteredEvent event, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String identity = event.platform().name() + "\u001f" + event.roomId() + "\u001f" + sessionId + "\u001f" + event.viewerId();
        String key = properties.redisKeyPrefix() + ":" + sha256(identity);
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    key,
                    "1",
                    properties.deduplicationTtl()
            ));
        } catch (RuntimeException exception) {
            logger.warn("Unable to deduplicate viewer welcome: {}", exception.getClass().getSimpleName());
            return true;
        }
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
