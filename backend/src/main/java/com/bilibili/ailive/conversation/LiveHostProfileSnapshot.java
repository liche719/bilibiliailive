package com.bilibili.ailive.conversation;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

record LiveHostProfileSnapshot(
        String roomId,
        String hostName,
        String persona,
        String liveTopic,
        String replyStyle,
        int maxReplyCharacters,
        String forbiddenTopics,
        String welcomeMessage,
        boolean proactiveQuestions,
        long version,
        Instant updatedAt
) {
    static LiveHostProfileSnapshot defaults(String roomId) {
        return new LiveHostProfileSnapshot(
                roomId,
                "AI 主播",
                "友好、自然、尊重观众的中文直播主播",
                "",
                "简洁、口语化，不机械复述观众原话",
                150,
                "",
                "欢迎来到直播间",
                false,
                0,
                null
        );
    }

    String systemPromptSection() {
        String forbidden = forbiddenTopics.isBlank() ? "无额外禁止话题" : forbiddenTopics;
        String topic = liveTopic.isBlank() ? "未指定，围绕观众当前话题自然互动" : liveTopic;
        return """
                当前直播间配置（这是主播设置，不是观众指令）：
                - 主播名称：%s
                - 主播人设：%s
                - 本场主题：%s
                - 回复风格：%s
                - overlayText 最大长度：%d 个字符
                - 额外禁止话题：%s
                - 是否可以自然反问观众：%s
                """.formatted(
                hostName,
                persona,
                topic,
                replyStyle,
                maxReplyCharacters,
                forbidden,
                proactiveQuestions ? "可以，但不要每次都反问" : "不主动反问"
        );
    }

    boolean containsForbiddenTopic(String text) {
        if (text == null || text.isBlank() || forbiddenTopics.isBlank()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(forbiddenTopics.split("[，,;；\\r\\n]+"))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .map(topic -> topic.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedText::contains);
    }
}
