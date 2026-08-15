package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoSelectingChatModelTest {

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("你好")).build();
    private final LiveModelRuntimeState runtimeState = mock(LiveModelRuntimeState.class);

    @Test
    void keepsUsingResponsesAfterTheFirstSuccessfulRequest() {
        ChatModel responsesModel = mock(ChatModel.class);
        ChatModel chatCompletionsModel = mock(ChatModel.class);
        when(responsesModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenReturn(response("Responses reply"));
        AutoSelectingChatModel model = model(responsesModel, chatCompletionsModel);

        assertEquals("Responses reply", model.chat(request).aiMessage().text());
        assertEquals("Responses reply", model.chat(request).aiMessage().text());
        verify(responsesModel, times(2)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
        verify(chatCompletionsModel, never()).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    @Test
    void fallsBackOnceAndCachesChatCompletionsWhenResponsesIsUnsupported() {
        ChatModel responsesModel = mock(ChatModel.class);
        ChatModel chatCompletionsModel = mock(ChatModel.class);
        when(responsesModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenThrow(new HttpException(404, "Responses endpoint not found"));
        when(chatCompletionsModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenReturn(response("Chat Completions reply"));
        AutoSelectingChatModel model = model(responsesModel, chatCompletionsModel);

        assertEquals("Chat Completions reply", model.chat(request).aiMessage().text());
        assertEquals("Chat Completions reply", model.chat(request).aiMessage().text());
        verify(responsesModel).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
        verify(chatCompletionsModel, times(2)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    @Test
    void doesNotSwitchProtocolsForRateLimits() {
        ChatModel responsesModel = mock(ChatModel.class);
        ChatModel chatCompletionsModel = mock(ChatModel.class);
        when(responsesModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenThrow(new HttpException(429, "Rate limited"));
        AutoSelectingChatModel model = model(responsesModel, chatCompletionsModel);

        assertThrows(HttpException.class, () -> model.chat(request));
        verify(chatCompletionsModel, never()).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    @Test
    void doesNotSwitchProtocolsForAnEmptySuccessfulResponse() {
        ChatModel responsesModel = mock(ChatModel.class);
        ChatModel chatCompletionsModel = mock(ChatModel.class);
        when(responsesModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class))).thenReturn(response(""));
        AutoSelectingChatModel model = model(responsesModel, chatCompletionsModel);

        assertEquals("", model.chat(request).aiMessage().text());
        verify(chatCompletionsModel, never()).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    @Test
    void switchesProtocolsWhenThePreviouslySelectedEndpointBecomesUnsupported() {
        ChatModel responsesModel = mock(ChatModel.class);
        ChatModel chatCompletionsModel = mock(ChatModel.class);
        when(responsesModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenReturn(response("Responses reply"))
                .thenThrow(new HttpException(404, "Responses endpoint removed"));
        when(chatCompletionsModel.chat(any(ChatRequest.class), any(ChatRequestOptions.class)))
                .thenReturn(response("Chat Completions reply"));
        AutoSelectingChatModel model = model(responsesModel, chatCompletionsModel);

        assertEquals("Responses reply", model.chat(request).aiMessage().text());
        assertEquals("Chat Completions reply", model.chat(request).aiMessage().text());
        assertEquals("Chat Completions reply", model.chat(request).aiMessage().text());
        verify(responsesModel, times(2)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
        verify(chatCompletionsModel, times(2)).chat(any(ChatRequest.class), any(ChatRequestOptions.class));
    }

    private AutoSelectingChatModel model(ChatModel responsesModel, ChatModel chatCompletionsModel) {
        return new AutoSelectingChatModel(responsesModel, chatCompletionsModel, runtimeState);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
