package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "langchain4j.open-ai")
public record LangChain4jOpenAiProperties(ChatModelProperties chatModel) {

    public LangChain4jOpenAiProperties {
        if (chatModel == null) {
            throw new IllegalStateException("langchain4j.open-ai.chat-model configuration is required");
        }
    }

    public record ChatModelProperties(
            ApiMode apiMode,
            String baseUrl,
            String apiKey,
            String modelName,
            Duration timeout
    ) {
        public ChatModelProperties {
            apiMode = apiMode == null ? ApiMode.AUTO : apiMode;
        }

        public boolean isConfigured() {
            return isPresent(baseUrl) && isPresent(apiKey) && isPresent(modelName);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }
    }

    public enum ApiMode {
        AUTO,
        RESPONSES,
        CHAT_COMPLETIONS
    }
}
