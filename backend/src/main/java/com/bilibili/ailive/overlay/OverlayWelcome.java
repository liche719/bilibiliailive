package com.bilibili.ailive.overlay;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OverlayWelcome(
        UUID id,
        String roomId,
        List<String> viewerNames,
        int totalViewers,
        String text,
        long displayDurationMs,
        Instant occurredAt
) {

    public OverlayWelcome {
        viewerNames = List.copyOf(viewerNames);
    }
}
