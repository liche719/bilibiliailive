package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamHostAssistantMemoryTest {

    @Test
    void keepsHistoryForTheSameViewerAndIsolatesDifferentViewers() {
        RecordingChatModel model = new RecordingChatModel();
        LiveHostMemoryProperties properties = new LiveHostMemoryProperties(
                8, 1000, java.time.Duration.ofMinutes(30), "test:chat-memory"
        );
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.maxMessages())
                .chatMemoryStore(new InMemoryChatMemoryStore())
                .build();
        StreamHostAssistant assistant = AiServices.builder(StreamHostAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(memoryProvider)
                .build();

        assistant.reply("MOCK:1000:viewer-1", "测试人设", "观众一：第一条弹幕");
        assistant.reply("MOCK:1000:viewer-1", "测试人设", "观众一：第二条弹幕");
        assistant.reply("MOCK:1000:viewer-2", "测试人设", "观众二：另一个用户");

        assertTrue(userTexts(model.requests().get(1)).stream().anyMatch(text -> text.contains("第一条弹幕")));
        assertFalse(userTexts(model.requests().get(2)).stream().anyMatch(text -> text.contains("第一条弹幕")));
    }

    private static List<String> userTexts(ChatRequest request) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .toList();
    }

    private static final class RecordingChatModel implements ChatModel {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"overlayText\":\"测试回复\",\"danmakuText\":null,\"sendDanmaku\":false}"))
                    .build();
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }
}
