package com.bilibili.ailive.conversation;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamHostAssistantContractTest {

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");

    @Test
    void bindsExplicitlyToTheLiveChatModel() throws NoSuchMethodException {
        AiService aiService = StreamHostAssistant.class.getAnnotation(AiService.class);
        Method reply = StreamHostAssistant.class.getMethod("reply", String.class, String.class, String.class);

        assertEquals(AiServiceWiringMode.EXPLICIT, aiService.wiringMode());
        assertEquals("liveChatModel", aiService.chatModel());
        assertEquals("liveHostChatMemoryProvider", aiService.chatMemoryProvider());
        assertEquals(Set.of("roomContextTools", "webSearchTools"), Set.of(aiService.tools()));
        assertTrue(reply.isAnnotationPresent(dev.langchain4j.service.SystemMessage.class));
        assertTrue(reply.isAnnotationPresent(UserMessage.class));
        assertTrue(reply.getParameters()[0].isAnnotationPresent(MemoryId.class));
        assertEquals("hostProfile", reply.getParameters()[1].getAnnotation(V.class).value());
        assertEquals("viewerInput", reply.getParameters()[2].getAnnotation(V.class).value());
        assertEquals(LiveHostReply.class, reply.getReturnType());
    }

    @Test
    void bindsExplicitlyToTheStreamingLiveChatModel() throws NoSuchMethodException {
        AiService aiService = StreamingStreamHostAssistant.class.getAnnotation(AiService.class);
        Method reply = StreamingStreamHostAssistant.class.getDeclaredMethod(
                "reply",
                String.class,
                String.class,
                String.class
        );

        assertEquals(AiServiceWiringMode.EXPLICIT, aiService.wiringMode());
        assertEquals("liveStreamingChatModel", aiService.streamingChatModel());
        assertEquals("liveHostChatMemoryProvider", aiService.chatMemoryProvider());
        assertEquals(Set.of("roomContextTools", "webSearchTools"), Set.of(aiService.tools()));
        assertTrue(reply.isAnnotationPresent(dev.langchain4j.service.SystemMessage.class));
        assertTrue(reply.isAnnotationPresent(UserMessage.class));
        assertTrue(reply.getParameters()[0].isAnnotationPresent(MemoryId.class));
        assertEquals("hostProfile", reply.getParameters()[1].getAnnotation(V.class).value());
        assertEquals("viewerInput", reply.getParameters()[2].getAnnotation(V.class).value());
        assertEquals(TokenStream.class, reply.getReturnType());
    }

    @Test
    void userPromptTemplateOnlyUsesTheStableViewerInputVariable() throws IOException {
        try (var resource = getClass().getClassLoader()
                .getResourceAsStream("prompts/live-host/reply-user-message.txt")) {
            assertNotNull(resource);
            String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> variables = TEMPLATE_VARIABLE.matcher(template).results()
                    .map(result -> result.group(1))
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(Set.of("viewerInput"), variables);
        }
    }

    @Test
    void systemPromptTemplateAcceptsTheHostProfileVariable() throws IOException {
        try (var resource = getClass().getClassLoader()
                .getResourceAsStream("prompts/live-host/system-message.txt")) {
            assertNotNull(resource);
            String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> variables = TEMPLATE_VARIABLE.matcher(template).results()
                    .map(result -> result.group(1))
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(Set.of("hostProfile"), variables);
        }
    }

    @Test
    void disablesTheChatModelWhenRequiredConfigurationIsMissing() {
        LangChain4jOpenAiProperties properties = new LangChain4jOpenAiProperties(
                new LangChain4jOpenAiProperties.ChatModelProperties(
                        LangChain4jOpenAiProperties.ApiMode.AUTO,
                        "", "", "", null
                )
        );

        ChatModel chatModel = new LangChain4jLiveHostConfiguration().liveChatModel(properties);

        assertInstanceOf(DisabledChatModel.class, chatModel);
    }

    @Test
    void createsTheNativeResponsesModelWhenConfigured() {
        ChatModel chatModel = configuredModel(LangChain4jOpenAiProperties.ApiMode.RESPONSES);

        ResilientChatModel resilient = assertInstanceOf(ResilientChatModel.class, chatModel);
        assertInstanceOf(OpenAiResponsesChatModel.class, resilient.delegate());
    }

    @Test
    void createsTheNativeChatCompletionsModelWhenConfigured() {
        ChatModel chatModel = configuredModel(LangChain4jOpenAiProperties.ApiMode.CHAT_COMPLETIONS);

        ResilientChatModel resilient = assertInstanceOf(ResilientChatModel.class, chatModel);
        assertInstanceOf(OpenAiChatModel.class, resilient.delegate());
    }

    @Test
    void createsTheAutomaticSelectorWhenConfigured() {
        ChatModel chatModel = configuredModel(LangChain4jOpenAiProperties.ApiMode.AUTO);

        ResilientChatModel resilient = assertInstanceOf(ResilientChatModel.class, chatModel);
        assertInstanceOf(AutoSelectingChatModel.class, resilient.delegate());
    }

    @Test
    void disablesTheStreamingModelWhenRequiredConfigurationIsMissing() {
        StreamingChatModel model = configuredStreamingModel(
                new LangChain4jOpenAiProperties(
                        new LangChain4jOpenAiProperties.ChatModelProperties(
                                LangChain4jOpenAiProperties.ApiMode.AUTO,
                                "", "", "", null
                        )
                )
        );

        assertInstanceOf(DisabledStreamingChatModel.class, model);
    }

    @Test
    void createsTheNativeResponsesStreamingModelWhenConfigured() {
        StreamingChatModel model = configuredStreamingModel(
                configuredProperties(LangChain4jOpenAiProperties.ApiMode.RESPONSES)
        );

        ResilientStreamingChatModel resilient = assertInstanceOf(ResilientStreamingChatModel.class, model);
        assertInstanceOf(OpenAiResponsesStreamingChatModel.class, resilient.delegate());
    }

    @Test
    void createsTheNativeChatCompletionsStreamingModelWhenConfigured() {
        StreamingChatModel model = configuredStreamingModel(
                configuredProperties(LangChain4jOpenAiProperties.ApiMode.CHAT_COMPLETIONS)
        );

        ResilientStreamingChatModel resilient = assertInstanceOf(ResilientStreamingChatModel.class, model);
        assertInstanceOf(OpenAiStreamingChatModel.class, resilient.delegate());
    }

    @Test
    void createsTheAutomaticStreamingSelectorWhenConfigured() {
        StreamingChatModel model = configuredStreamingModel(
                configuredProperties(LangChain4jOpenAiProperties.ApiMode.AUTO)
        );

        ResilientStreamingChatModel resilient = assertInstanceOf(ResilientStreamingChatModel.class, model);
        assertInstanceOf(AutoSelectingStreamingChatModel.class, resilient.delegate());
    }

    @Test
    void createsChatMemoriesBackedByTheConfiguredStore() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        when(store.getMessages("viewer-memory")).thenReturn(java.util.List.of());
        LiveHostMemoryProperties properties = new LiveHostMemoryProperties(
                8, 1000, java.time.Duration.ofMinutes(30), "test:chat-memory"
        );

        new LangChain4jLiveHostConfiguration()
                .liveHostChatMemoryProvider(store, properties)
                .get("viewer-memory")
                .messages();

        verify(store).getMessages("viewer-memory");
    }

    private static ChatModel configuredModel(LangChain4jOpenAiProperties.ApiMode apiMode) {
        return new LangChain4jLiveHostConfiguration().liveChatModel(configuredProperties(apiMode));
    }

    private static LangChain4jOpenAiProperties configuredProperties(
            LangChain4jOpenAiProperties.ApiMode apiMode
    ) {
        return new LangChain4jOpenAiProperties(
                new LangChain4jOpenAiProperties.ChatModelProperties(
                        apiMode,
                        "https://api.example.com/v1",
                        "test-key",
                        "test-model",
                        null
                )
        );
    }

    private static StreamingChatModel configuredStreamingModel(LangChain4jOpenAiProperties properties) {
        return new LangChain4jLiveHostConfiguration().liveStreamingChatModel(
                properties,
                new LiveModelRuntimeState(properties),
                new SimpleMeterRegistry()
        );
    }
}
