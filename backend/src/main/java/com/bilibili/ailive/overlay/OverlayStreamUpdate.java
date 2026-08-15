package com.bilibili.ailive.overlay;

import java.util.UUID;

public record OverlayStreamUpdate(
        UUID candidateId,
        String text,
        boolean completed
) {
}
