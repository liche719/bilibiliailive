package com.bilibili.ailive.runtime;

import com.bilibili.ailive.liveplatform.LiveOutputMode;

import java.time.Instant;

public record RuntimeControlResponse(
        boolean paused,
        LiveOutputMode outputMode,
        String actor,
        Instant changedAt
) {
}
