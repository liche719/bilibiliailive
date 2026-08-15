package com.bilibili.ailive.liveplatform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MockViewerEntryRequest(
        @NotBlank @Size(max = 64) String roomId,
        @NotBlank @Size(max = 128) String viewerId,
        @Size(max = 128) String viewerName,
        @Size(max = 128) String sessionId
) {
}
