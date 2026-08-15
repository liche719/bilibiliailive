package com.bilibili.ailive.tts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.tts")
public record TtsProperties(
        boolean enabled,
        String voice,
        int rate,
        int volume,
        Path outputDirectory,
        Duration timeout
) {
    public TtsProperties {
        if (voice == null || voice.isBlank()) {
            throw new IllegalArgumentException("TTS voice is required");
        }
        if (rate < -10 || rate > 10) {
            throw new IllegalArgumentException("TTS rate must be between -10 and 10");
        }
        if (volume < 0 || volume > 100) {
            throw new IllegalArgumentException("TTS volume must be between 0 and 100");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("TTS output directory is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("TTS timeout must be positive");
        }
    }
}
