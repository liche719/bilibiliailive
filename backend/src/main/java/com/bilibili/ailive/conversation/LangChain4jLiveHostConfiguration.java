package com.bilibili.ailive.conversation;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.output.JsonSchemas;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class LangChain4jLiveHostConfiguration {

    @Bean("liveHostChatMemoryProvider")
    ChatMemoryProvider liveHostChatMemoryProvider(
            ChatMemoryStore chatMemoryStore,
            LiveHostMemoryProperties properties
    ) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.maxMessages())
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    @Bean("liveChatModel")
    ChatModel liveChatModel(
            LangChain4jOpenAiProperties properties,
            LiveModelRuntimeState runtimeState,
            MeterRegistry meterRegistry
    ) {
        LangChain4jOpenAiProperties.ChatModelProperties model = properties.chatModel();
        if (!model.isConfigured()) {
            return new DisabledChatModel();
        }
        Duration timeout = model.timeout() == null ? Duration.ofSeconds(8) : model.timeout();
        ChatModel selectedModel = switch (model.apiMode()) {
            case RESPONSES -> responsesModel(model, timeout);
            case CHAT_COMPLETIONS -> chatCompletionsModel(model, timeout);
            case AUTO -> new AutoSelectingChatModel(
                    responsesModel(model, timeout),
                    chatCompletionsModel(model, timeout),
                    runtimeState
            );
        };
        return new ResilientChatModel(selectedModel, runtimeState, meterRegistry);
    }

    ChatModel liveChatModel(LangChain4jOpenAiProperties properties) {
        return liveChatModel(properties, new LiveModelRuntimeState(properties), new SimpleMeterRegistry());
    }

    @Bean("liveStreamingChatModel")
    StreamingChatModel liveStreamingChatModel(
            LangChain4jOpenAiProperties properties,
            LiveModelRuntimeState runtimeState,
            MeterRegistry meterRegistry
    ) {
        LangChain4jOpenAiProperties.ChatModelProperties model = properties.chatModel();
        if (!model.isConfigured()) {
            return new DisabledStreamingChatModel();
        }
        Duration timeout = model.timeout() == null ? Duration.ofSeconds(8) : model.timeout();
        ResponseFormat responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchemas.jsonSchemaFrom(LiveHostReply.class).orElseThrow())
                .build();
        StreamingChatModel selectedModel = switch (model.apiMode()) {
            case RESPONSES -> responsesStreamingModel(model, timeout, responseFormat);
            case CHAT_COMPLETIONS -> chatCompletionsStreamingModel(model, timeout, responseFormat);
            case AUTO -> new AutoSelectingStreamingChatModel(
                    responsesStreamingModel(model, timeout, responseFormat),
                    chatCompletionsStreamingModel(model, timeout, responseFormat),
                    runtimeState
            );
        };
        return new ResilientStreamingChatModel(selectedModel, runtimeState, meterRegistry);
    }

    private static ChatModel responsesModel(
            LangChain4jOpenAiProperties.ChatModelProperties model,
            Duration timeout
    ) {
        JdkHttpClientBuilder httpClientBuilder = new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
        return OpenAiResponsesChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(model.baseUrl())
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .store(false)
                .build();
    }

    private static ChatModel chatCompletionsModel(
            LangChain4jOpenAiProperties.ChatModelProperties model,
            Duration timeout
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(model.baseUrl())
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .timeout(timeout)
                .maxRetries(0)
                .build();
    }

    private static StreamingChatModel responsesStreamingModel(
            LangChain4jOpenAiProperties.ChatModelProperties model,
            Duration timeout,
            ResponseFormat responseFormat
    ) {
        JdkHttpClientBuilder httpClientBuilder = new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
        return OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(model.baseUrl())
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .store(false)
                .responseFormat(responseFormat)
                .strictJsonSchema(true)
                .build();
    }

    private static StreamingChatModel chatCompletionsStreamingModel(
            LangChain4jOpenAiProperties.ChatModelProperties model,
            Duration timeout,
            ResponseFormat responseFormat
    ) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(model.baseUrl())
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .timeout(timeout)
                .responseFormat(responseFormat)
                .strictJsonSchema(true)
                .build();
    }
}
