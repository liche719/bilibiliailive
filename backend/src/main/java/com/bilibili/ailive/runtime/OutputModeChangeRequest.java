package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;
import jakarta.validation.constraints.NotNull;

public record OutputModeChangeRequest(@NotNull LiveOutputMode outputMode) {
}
