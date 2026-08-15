package com.bilibili.ailive.conversation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class RedisReplyAdmissionService {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LiveReplyPolicyProperties properties;
    private final LiveReplyMetrics metrics;

    RedisReplyAdmissionService(
            StringRedisTemplate redisTemplate,
            LiveReplyPolicyProperties properties,
            LiveReplyMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    ReplyAdmissionDecision evaluate(ReplyRequest request) {
        String userCooldownKey = RedisKeyFactory.opaqueKey(
                properties.redisKeyPrefix(),
                "user-cooldown",
                request.platform(),
                request.roomId(),
                request.senderId()
        );
        Boolean cooldownAcquired = redisTemplate.opsForValue()
                .setIfAbsent(userCooldownKey, "1", properties.userCooldown());
        if (!Boolean.TRUE.equals(cooldownAcquired)) {
            metrics.recordAdmission("user_cooldown");
            return ReplyAdmissionDecision.reject("用户发言过于频繁，本条未调用模型");
        }

        String modelCallWindowKey = RedisKeyFactory.opaqueKey(
                properties.redisKeyPrefix(),
                "model-call-window",
                "global"
        );
        Long modelCallCount = redisTemplate.execute(
                INCREMENT_WITH_TTL,
                List.of(modelCallWindowKey),
                Long.toString(properties.modelCallRateWindow().toMillis())
        );
        if (modelCallCount == null) {
            throw new IllegalStateException("Redis did not return the global model call count");
        }
        if (modelCallCount > properties.maxModelCallsPerWindow()) {
            metrics.recordAdmission("global_limit");
            return ReplyAdmissionDecision.reject("全局模型调用频率已达到上限，本条未调用模型");
        }
        metrics.recordAdmission("accepted");
        return ReplyAdmissionDecision.allow();
    }
}
