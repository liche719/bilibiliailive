package com.bilibili.ailive.conversation;

public record LiveHostReply(
        String overlayText,
        String danmakuText,
        boolean sendDanmaku
) {

    public LiveHostReply {
        overlayText = normalize(overlayText);
        danmakuText = normalize(danmakuText);
        if (!sendDanmaku) {
            danmakuText = null;
        }
    }

    public LiveHostReply withoutDanmaku() {
        return new LiveHostReply(overlayText, null, false);
    }

    LiveHostReply withSearchAttribution() {
        if (overlayText == null || overlayText.contains("调用工具：搜索")) {
            return this;
        }
        return new LiveHostReply(overlayText + "\n调用工具：搜索", danmakuText, sendDanmaku);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
