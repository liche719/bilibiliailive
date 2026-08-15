package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    private final StringRedisTemplate redisTemplate;
    private final LiveHostMemoryProperties properties;

    RedisChatMemoryStore(StringRedisTemplate redisTemplate, LiveHostMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = key(memoryId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (RuntimeException exception) {
            logger.warn("Discarding malformed Redis chat memory entry: keyHash={}", keyHash(key));
            redisTemplate.delete(key);
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        String key = key(memoryId);
        if (messages.isEmpty()) {
            redisTemplate.delete(key);
            return;
        }
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(key, json, properties.ttl());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        Objects.requireNonNull(memoryId, "memoryId must not be null");
        return RedisKeyFactory.opaqueKey(properties.redisKeyPrefix(), "viewer", memoryId.toString());
    }

    private static String keyHash(String key) {
        return key.substring(key.lastIndexOf(':') + 1);
    }

}
