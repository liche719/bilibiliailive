package com.bilibili.ailive.tts;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LocalTtsService {

    private static final Logger logger = LoggerFactory.getLogger(LocalTtsService.class);
    private static final Pattern TOOL_ATTRIBUTION = Pattern.compile("(?m)^\\s*调用工具：搜索\\s*$");

    private final TtsProperties properties;
    private final LocalSpeechSynthesizer synthesizer;
    private volatile boolean muted;
    private volatile int rate;
    private volatile int volume;

    public LocalTtsService(TtsProperties properties, LocalSpeechSynthesizer synthesizer) {
        this.properties = properties;
        this.synthesizer = synthesizer;
        this.rate = properties.rate();
        this.volume = properties.volume();
    }

    @PostConstruct
    void initialize() throws Exception {
        Files.createDirectories(properties.outputDirectory());
    }

    public Optional<SpeechAsset> synthesize(UUID candidateId, String replyText) {
        if (!properties.enabled() || muted || replyText == null) {
            return Optional.empty();
        }
        String speechText = speechText(replyText);
        if (speechText.isBlank()) {
            return Optional.empty();
        }
        Path output = audioPath(candidateId);
        try {
            synthesizer.synthesize(speechText, output, properties.voice(), rate);
            return Optional.of(new SpeechAsset(
                    "/api/tts/audio/" + candidateId,
                    wavDurationMillis(output)
            ));
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(output);
            } catch (Exception ignored) {
                // The next synthesis for this candidate uses a fresh UUID in normal operation.
            }
            logger.warn("Local TTS failed for candidate {}: {}", candidateId, exception.getMessage());
            return Optional.empty();
        }
    }

    public TtsSettings settings() {
        return new TtsSettings(properties.enabled(), muted, properties.voice(), rate, volume);
    }

    public synchronized TtsSettings update(TtsSettingsRequest request) {
        muted = request.muted();
        rate = request.rate();
        volume = request.volume();
        return settings();
    }

    public Resource audio(UUID candidateId) {
        try {
            Path path = audioPath(candidateId);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("TTS audio was not found");
            }
            return new UrlResource(path.toUri());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read TTS audio", exception);
        }
    }

    static String speechText(String replyText) {
        return TOOL_ATTRIBUTION.matcher(replyText).replaceAll("").strip();
    }

    private Path audioPath(UUID candidateId) {
        return properties.outputDirectory().resolve(candidateId + ".wav").normalize();
    }

    private static long wavDurationMillis(Path path) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            long frames = stream.getFrameLength();
            float frameRate = stream.getFormat().getFrameRate();
            return frameRate <= 0 ? 0 : Math.round(frames * 1_000d / frameRate);
        }
    }
}
