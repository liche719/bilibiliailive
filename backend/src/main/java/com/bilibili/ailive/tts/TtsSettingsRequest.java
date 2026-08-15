package com.bilibili.ailive.tts;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record TtsSettingsRequest(
        boolean muted,
        @Min(-10) @Max(10) int rate,
        @Min(0) @Max(100) int volume
) {
}
