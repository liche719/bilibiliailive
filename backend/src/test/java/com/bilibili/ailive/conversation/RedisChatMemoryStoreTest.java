package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String MEMORY_ID = "BILIBILI:1000:viewer-secret-id";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisChatMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisChatMemoryStore(
                redisTemplate,
                new LiveHostMemoryProperties(8, 1000, TTL, "ai-live:chat-memory")
        );
    }

    @Test
    void writesLangChain4jJsonWithTtlAndAnOpaqueKey() {
        List<ChatMessage> messages = List.of(UserMessage.from("你好"), AiMessage.from("你好呀"));

        store.updateMessages(MEMORY_ID, messages);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), jsonCaptor.capture(), org.mockito.ArgumentMatchers.eq(TTL));
        assertTrue(keyCaptor.getValue().startsWith("ai-live:chat-memory:viewer:"));
        assertFalse(keyCaptor.getValue().contains("viewer-secret-id"));
        assertEquals(messages, ChatMessageDeserializer.messagesFromJson(jsonCaptor.getValue()));
    }

    @Test
    void readsMessagesUsingLangChain4jDeserializer() {
        List<ChatMessage> messages = List.of(UserMessage.from("上一条消息"), AiMessage.from("上一条回复"));
        when(valueOperations.get(anyString())).thenReturn(ChatMessageSerializer.messagesToJson(messages));

        assertEquals(messages, store.getMessages(MEMORY_ID));
    }

    @Test
    void deletesTheEntryWhenMessagesAreEmpty() {
        store.updateMessages(MEMORY_ID, List.of());

        verify(redisTemplate).delete(anyString());
        verify(valueOperations, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void deletesMalformedJsonAndContinuesWithEmptyMemory() {
        when(valueOperations.get(anyString())).thenReturn("not-json");

        assertTrue(store.getMessages(MEMORY_ID).isEmpty());
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void returnsEmptyMemoryWhenTheKeyDoesNotExist() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertTrue(store.getMessages(MEMORY_ID).isEmpty());
        verify(redisTemplate, never()).delete(anyString());
    }
}
