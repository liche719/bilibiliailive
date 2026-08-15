package com.bilibili.ailive.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class ResilientStreamingChatModelTest {

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("你好")).build();

    @Test
    void retriesOneTransientFailureBeforeAnyStreamEvent() {
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = handler(invocation);
            if (attempts.getAndIncrement() == 0) {
                handler.onError(new HttpException(503, "Unavailable"));
            } else {
                handler.onCompleteResponse(response("恢复成功"));
            }
            return null;
        }).when(delegate).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        ResilientStreamingChatModel model = model(delegate);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);

        model.chat(request, downstream);

        verify(delegate, timeout(2_000).times(2))
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(downstream, timeout(2_000)).onCompleteResponse(any(ChatResponse.class));
    }

    @Test
    void doesNotRetryAfterPartialOutput() throws InterruptedException {
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        HttpException failure = new HttpException(503, "Unavailable after output");
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = handler(invocation);
            handler.onPartialResponse("partial");
            handler.onError(failure);
            return null;
        }).when(delegate).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        ResilientStreamingChatModel model = model(delegate);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);

        model.chat(request, downstream);
        Thread.sleep(900);

        verify(delegate).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(downstream).onPartialResponse("partial");
        verify(downstream).onError(failure);
    }

    private static ResilientStreamingChatModel model(StreamingChatModel delegate) {
        LangChain4jOpenAiProperties properties = new LangChain4jOpenAiProperties(
                new LangChain4jOpenAiProperties.ChatModelProperties(
                        LangChain4jOpenAiProperties.ApiMode.RESPONSES,
                        "https://example.invalid/v1",
                        "test-key",
                        "test-model",
                        Duration.ofSeconds(1)
                )
        );
        return new ResilientStreamingChatModel(
                delegate,
                new LiveModelRuntimeState(properties),
                new SimpleMeterRegistry()
        );
    }

    private static StreamingChatResponseHandler handler(org.mockito.invocation.InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
