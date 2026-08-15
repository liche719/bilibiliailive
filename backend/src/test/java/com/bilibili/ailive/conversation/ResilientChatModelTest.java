package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientChatModelTest {

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("你好")).build();

    @Test
    void retriesOneTransientGatewayFailure() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenThrow(new HttpException(503, "Unavailable"))
                .thenReturn(response("恢复成功"));
        ResilientChatModel model = model(delegate);

        assertEquals("恢复成功", model.chat(request).aiMessage().text());
        verify(delegate, times(2)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    @Test
    void opensCircuitAfterThreeConsecutivePermanentFailures() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenThrow(new HttpException(401, "Unauthorized"));
        ResilientChatModel model = model(delegate);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(HttpException.class, () -> model.chat(request));
        }
        assertThrows(ModelCircuitOpenException.class, () -> model.chat(request));
        verify(delegate, times(3)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    private static ResilientChatModel model(ChatModel delegate) {
        LangChain4jOpenAiProperties properties = new LangChain4jOpenAiProperties(
                new LangChain4jOpenAiProperties.ChatModelProperties(
                        LangChain4jOpenAiProperties.ApiMode.RESPONSES,
                        "https://example.invalid/v1",
                        "test-key",
                        "test-model",
                        Duration.ofSeconds(1)
                )
        );
        return new ResilientChatModel(
                delegate,
                new LiveModelRuntimeState(properties),
                new SimpleMeterRegistry()
        );
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
