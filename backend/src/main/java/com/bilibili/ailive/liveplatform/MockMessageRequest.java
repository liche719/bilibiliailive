package com.bilibili.ailive.liveplatform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MockMessageRequest(
        @NotBlank @Size(max = 64) String roomId,
        @Size(max = 128) String senderId,
        @Size(max = 128) String senderName,
        @NotBlank @Size(max = 280) String messageText
) {
}
