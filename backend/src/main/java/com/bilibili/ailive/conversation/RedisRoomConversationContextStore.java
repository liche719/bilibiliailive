package com.bilibili.ailive.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
class RedisRoomConversationContextStore implements RoomConversationContextStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisRoomConversationContextStore.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String EMPTY_CONTEXT = "（暂无此前公开对话）";
    private static final DefaultRedisScript<Long> UPSERT_WINDOW_ENTRY = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            local count = redis.call('ZCARD', KEYS[2])
            local maxEntries = tonumber(ARGV[4])
            if count > maxEntries then
                local expiredIds = redis.call('ZRANGE', KEYS[2], 0, count - maxEntries - 1)
                if #expiredIds > 0 then
                    redis.call('HDEL', KEYS[1], unpack(expiredIds))
                    redis.call('ZREM', KEYS[2], unpack(expiredIds))
                end
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('PEXPIRE', KEYS[2], ARGV[5])
            return redis.call('ZCARD', KEYS[2])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoomContextProperties properties;
    private final LiveReplyMetrics metrics;

    RedisRoomConversationContextStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RoomContextProperties properties,
            LiveReplyMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String recentContext(String memoryId) {
        return metrics.recordRoomContextRead(() -> readRecentContext(memoryId));
    }

    private String readRecentContext(String memoryId) {
        RoomIdentity room = RoomIdentity.fromMemoryId(memoryId);
        try {
            Set<String> messageIds = redisTemplate.opsForZSet().range(room.orderKey(properties.redisKeyPrefix()), 0, -1);
            if (messageIds == null || messageIds.isEmpty()) {
                return EMPTY_CONTEXT;
            }
            List<Object> values = redisTemplate.opsForHash()
                    .multiGet(room.entriesKey(properties.redisKeyPrefix()), new ArrayList<>(messageIds));
            List<String> lines = new ArrayList<>(values.size() * 2);
            for (Object value : values) {
                if (!(value instanceof String json)) {
                    continue;
                }
                try {
                    RoomConversationEntry entry = objectMapper.readValue(json, RoomConversationEntry.class);
                    lines.add("[%s] %s：%s".formatted(
                            TIME_FORMATTER.format(entry.occurredAt()), entry.viewerName(), entry.viewerMessage()));
                    if (entry.hostReply() != null && !entry.hostReply().isBlank()) {
                        lines.add("AI 主播回复 %s：%s".formatted(entry.viewerName(), entry.hostReply()));
                    }
                } catch (JsonProcessingException exception) {
                    logger.warn("Ignoring malformed room conversation context entry");
                }
            }
            return lines.isEmpty() ? EMPTY_CONTEXT : String.join("\n", lines);
        } catch (RuntimeException exception) {
            logger.warn("Unable to read room conversation context; continuing without shared context: {}",
                    exception.getClass().getSimpleName());
            return EMPTY_CONTEXT;
        }
    }

    @Override
    public void observe(ReplyRequest request) {
        upsert(request, null);
    }

    @Override
    public void attachHostReply(ReplyRequest request, String hostReply) {
        upsert(request, hostReply);
    }

    private void upsert(ReplyRequest request, String hostReply) {
        metrics.recordRoomContextWrite(() -> doUpsert(request, hostReply));
    }

    private void doUpsert(ReplyRequest request, String hostReply) {
        try {
            RoomIdentity room = new RoomIdentity(request.platform(), request.roomId());
            String value = objectMapper.writeValueAsString(new RoomConversationEntry(
                    request.messageId(),
                    request.senderName(),
                    request.messageText(),
                    hostReply,
                    request.occurredAt()
            ));
            redisTemplate.execute(
                    UPSERT_WINDOW_ENTRY,
                    List.of(
                            room.entriesKey(properties.redisKeyPrefix()),
                            room.orderKey(properties.redisKeyPrefix())
                    ),
                    request.messageId(),
                    Long.toString(request.occurredAt().toEpochMilli()),
                    value,
                    Integer.toString(properties.maxEntries()),
                    Long.toString(properties.ttl().toMillis())
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            logger.warn("Unable to update room conversation context: {}", exception.getClass().getSimpleName());
        }
    }

    private record RoomIdentity(String platform, String roomId) {

        static RoomIdentity fromMemoryId(String memoryId) {
            String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 3);
            if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Invalid live host memory ID");
            }
            return new RoomIdentity(parts[0], parts[1]);
        }

        String entriesKey(String prefix) {
            return baseKey(prefix) + ":entries";
        }

        String orderKey(String prefix) {
            return baseKey(prefix) + ":order";
        }

        private String baseKey(String prefix) {
            return RedisKeyFactory.opaqueKey(prefix, "room", platform, roomId);
        }
    }
}
