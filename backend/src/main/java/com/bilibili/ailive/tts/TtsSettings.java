package com.bilibili.ailive.tts;

public record TtsSettings(boolean enabled, boolean muted, String voice, int rate, int volume) {
}
