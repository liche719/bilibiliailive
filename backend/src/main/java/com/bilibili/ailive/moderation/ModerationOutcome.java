package com.bilibili.ailive.moderation;

public record ModerationOutcome(boolean allowed, String reason) {

    public static ModerationOutcome allow() {
        return new ModerationOutcome(true, null);
    }

    public static ModerationOutcome block(String reason) {
        return new ModerationOutcome(false, reason);
    }
}
