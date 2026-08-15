package com.bilibili.ailive.tts;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
final class WindowsSapiSpeechSynthesizer implements LocalSpeechSynthesizer {

    private static final String SCRIPT = """
            Add-Type -AssemblyName System.Speech
            $speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $speaker.SelectVoice($env:AI_LIVE_TTS_VOICE)
            $speaker.Rate = [int]$env:AI_LIVE_TTS_RATE
            $speaker.Volume = 100
            $speaker.SetOutputToWaveFile($env:AI_LIVE_TTS_OUTPUT)
            try { $speaker.Speak($env:AI_LIVE_TTS_TEXT) } finally { $speaker.Dispose() }
            """;

    private final TtsProperties properties;

    WindowsSapiSpeechSynthesizer(TtsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void synthesize(String text, Path output, String voice, int rate) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT
        );
        builder.redirectErrorStream(true);
        builder.environment().put("AI_LIVE_TTS_TEXT", text);
        builder.environment().put("AI_LIVE_TTS_OUTPUT", output.toAbsolutePath().toString());
        builder.environment().put("AI_LIVE_TTS_VOICE", voice);
        builder.environment().put("AI_LIVE_TTS_RATE", Integer.toString(rate));
        Process process = builder.start();
        if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Local TTS timed out");
        }
        if (process.exitValue() != 0) {
            String outputText = new String(process.getInputStream().readAllBytes());
            throw new IllegalStateException("Local TTS failed: " + outputText.strip());
        }
    }
}
