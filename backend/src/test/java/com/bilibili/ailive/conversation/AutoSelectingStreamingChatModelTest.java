package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AutoSelectingStreamingChatModelTest {

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("你好")).build();
    private final LiveModelRuntimeState runtimeState = mock(LiveModelRuntimeState.class);

    @Test
    void keepsUsingResponsesAfterTheFirstSuccessfulStream() {
        StreamingChatModel responsesModel = mock(StreamingChatModel.class);
        StreamingChatModel chatCompletionsModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            handler(invocation).onCompleteResponse(response("Responses reply"));
            return null;
        }).when(responsesModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AutoSelectingStreamingChatModel model = model(responsesModel, chatCompletionsModel);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);

        model.chat(request, downstream);
        model.chat(request, downstream);

        verify(responsesModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(chatCompletionsModel, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(downstream, times(2)).onCompleteResponse(any(ChatResponse.class));
    }

    @Test
    void fallsBackOnceAndCachesChatCompletionsWhenResponsesIsUnsupported() {
        StreamingChatModel responsesModel = mock(StreamingChatModel.class);
        StreamingChatModel chatCompletionsModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            handler(invocation).onError(new HttpException(404, "Responses endpoint not found"));
            return null;
        }).when(responsesModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            handler(invocation).onCompleteResponse(response("Chat Completions reply"));
            return null;
        }).when(chatCompletionsModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AutoSelectingStreamingChatModel model = model(responsesModel, chatCompletionsModel);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);

        model.chat(request, downstream);
        model.chat(request, downstream);

        verify(responsesModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(chatCompletionsModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(downstream, times(2)).onCompleteResponse(any(ChatResponse.class));
    }

    @Test
    void doesNotSwitchProtocolsAfterAnyPartialOutputWasEmitted() {
        StreamingChatModel responsesModel = mock(StreamingChatModel.class);
        StreamingChatModel chatCompletionsModel = mock(StreamingChatModel.class);
        HttpException failure = new HttpException(404, "Connection failed after output");
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = handler(invocation);
            handler.onPartialResponse("partial");
            handler.onError(failure);
            return null;
        }).when(responsesModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        AutoSelectingStreamingChatModel model = model(responsesModel, chatCompletionsModel);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);

        model.chat(request, downstream);

        verify(downstream).onPartialResponse("partial");
        verify(downstream).onError(failure);
        verify(chatCompletionsModel, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private AutoSelectingStreamingChatModel model(
            StreamingChatModel responsesModel,
            StreamingChatModel chatCompletionsModel
    ) {
        return new AutoSelectingStreamingChatModel(responsesModel, chatCompletionsModel, runtimeState);
    }

    private static StreamingChatResponseHandler handler(org.mockito.invocation.InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
