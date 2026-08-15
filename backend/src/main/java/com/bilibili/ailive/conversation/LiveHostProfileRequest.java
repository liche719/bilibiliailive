package com.bilibili.ailive.conversation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LiveHostProfileRequest(
        @NotBlank @Size(max = 80) String hostName,
        @NotBlank @Size(max = 1000) String persona,
        @NotNull @Size(max = 500) String liveTopic,
        @NotBlank @Size(max = 500) String replyStyle,
        @Min(20) @Max(160) int maxReplyCharacters,
        @NotNull @Size(max = 1000) String forbiddenTopics,
        @NotNull @Size(max = 500) String welcomeMessage,
        boolean proactiveQuestions
) {
}
