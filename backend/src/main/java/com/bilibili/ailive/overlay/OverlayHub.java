package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;
import com.bilibili.ailive.shared.SseEmitterRegistry;
import com.bilibili.ailive.tts.LocalTtsService;
import com.bilibili.ailive.tts.SpeechAsset;
import com.bilibili.ailive.tts.TtsSettings;
import com.bilibili.ailive.tts.TtsProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OverlayHub implements OverlayPublisher {

    private static final String START_EVENT = "overlay-stream-start";
    private static final String UPDATE_EVENT = "overlay-stream";
    private static final String COMPLETE_EVENT = "overlay";
    private static final String CLEAR_EVENT = "overlay-clear";
    private static final String REPLY_RECEIVED_EVENT = "overlay-reply-received";
    private static final String REPLY_START_EVENT = "overlay-reply-start";
    private static final String REPLY_FINISH_EVENT = "overlay-reply-finish";
    private static final String REPLY_OUTCOME_EVENT = "overlay-reply-outcome";
    private static final String WELCOME_EVENT = "overlay-welcome";
    private static final String TTS_SETTINGS_EVENT = "overlay-tts-settings";

    private final SseEmitterRegistry registry;
    private final Duration characterInterval;
    private final BlockingQueue<Playback> pendingReplies;
    private final ExecutorService playbackExecutor;
    private final AtomicLong generation = new AtomicLong();
    private final LocalTtsService ttsService;
    private final Object eventLock = new Object();
    private volatile boolean closed;
    private volatile boolean playbackActive;

    @Autowired
    OverlayHub(OverlayStreamProperties properties, LocalTtsService ttsService) {
        this(properties, new SseEmitterRegistry(), ttsService);
    }

    OverlayHub(OverlayStreamProperties properties, SseEmitterRegistry registry) {
        this(properties, registry, new LocalTtsService(
                new TtsProperties(false, "Microsoft Huihui Desktop", 0, 85,
                        java.nio.file.Path.of(".local", "tts"), Duration.ofSeconds(30)),
                (text, output, voice, rate) -> { }
        ));
    }

    OverlayHub(OverlayStreamProperties properties, SseEmitterRegistry registry, LocalTtsService ttsService) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.characterInterval = properties.characterInterval();
        this.ttsService = Objects.requireNonNull(ttsService, "ttsService");
        this.pendingReplies = new ArrayBlockingQueue<>(properties.maxPendingReplies());
        this.playbackExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "overlay-stream-player");
            thread.setDaemon(true);
            return thread;
        });
        playbackExecutor.execute(this::playbackLoop);
    }

    public SseEmitter subscribe() {
        return registry.subscribe();
    }

    @Override
    public void replyReceived(String messageId, String senderName, String sourceText) {
        send(REPLY_RECEIVED_EVENT, new OverlayReplyReceived(messageId, senderName, sourceText));
    }

    @Override
    public void replyStarted(String messageId, String senderName, String sourceText) {
        send(REPLY_START_EVENT, new OverlayReplyStart(messageId, senderName, sourceText));
    }

    @Override
    public void replyFinished(String messageId) {
        send(REPLY_FINISH_EVENT, messageId);
    }

    @Override
    public void replyOutcome(ReplyCandidateResponse candidate) {
        send(REPLY_OUTCOME_EVENT, candidate);
    }

    @Override
    public void welcome(OverlayWelcome welcome) {
        send(WELCOME_EVENT, welcome);
    }

    @Override
    public boolean isReplyActive() {
        return playbackActive || !pendingReplies.isEmpty();
    }

    @Override
    public void publish(ReplyCandidateResponse payload) {
        Objects.requireNonNull(payload, "payload");
        if (closed || registry.subscriberCount() == 0) {
            return;
        }
        Playback playback = new Playback(generation.get(), payload);
        if (!pendingReplies.offer(playback)) {
            pendingReplies.poll();
            pendingReplies.offer(playback);
        }
    }

    public void clear() {
        generation.incrementAndGet();
        pendingReplies.clear();
        synchronized (eventLock) {
            send(CLEAR_EVENT, "");
        }
    }

    public void ttsSettingsChanged(TtsSettings settings) {
        send(TTS_SETTINGS_EVENT, settings);
    }

    private void send(String eventName, Object payload) {
        registry.send(eventName, payload);
    }

    @Scheduled(fixedDelayString = "${app.sse.heartbeat-interval:PT15S}")
    void heartbeat() {
        registry.heartbeat();
    }

    public int subscriberCount() {
        return registry.subscriberCount();
    }

    @PreDestroy
    void close() {
        closed = true;
        generation.incrementAndGet();
        pendingReplies.clear();
        playbackExecutor.shutdownNow();
    }

    private void playbackLoop() {
        while (!closed) {
            try {
                Playback playback = pendingReplies.take();
                stream(playback);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void stream(Playback playback) throws InterruptedException {
        playbackActive = true;
        try {
            long playbackStartedAt = System.nanoTime();
            SpeechAsset speech = ttsService.synthesize(
                    playback.candidate().id(),
                    playback.candidate().candidateText()
            ).orElse(null);
            if (!isCurrent(playback)) {
                return;
            }
            TtsSettings settings = ttsService.settings();
            if (registry.subscriberCount() == 0
                    || !sendIfCurrent(playback, START_EVENT, OverlayStreamStart.from(
                    playback.candidate(), speech == null ? null : speech.audioUrl(), settings.volume()
            ))) {
                return;
            }
            String text = playback.candidate().candidateText();
            if (text == null || text.isEmpty()) {
                sendIfCurrent(playback, COMPLETE_EVENT, playback.candidate());
                return;
            }
            int endIndex = 0;
            while (endIndex < text.length()) {
                endIndex += Character.charCount(text.codePointAt(endIndex));
                boolean completed = endIndex == text.length();
                OverlayStreamUpdate update = new OverlayStreamUpdate(
                        playback.candidate().id(),
                        text.substring(0, endIndex),
                        completed
                );
                if (!sendIfCurrent(playback, UPDATE_EVENT, update)) {
                    return;
                }
                if (!completed && !characterInterval.isZero()) {
                    TimeUnit.NANOSECONDS.sleep(characterInterval.toNanos());
                }
            }
            if (speech != null) {
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - playbackStartedAt);
                long remainingMillis = speech.durationMillis() - elapsedMillis;
                waitForAudio(playback, remainingMillis);
            }
            sendIfCurrent(playback, COMPLETE_EVENT, playback.candidate());
        } finally {
            playbackActive = false;
        }
    }

    private boolean sendIfCurrent(Playback playback, String eventName, Object payload) {
        synchronized (eventLock) {
            if (!isCurrent(playback)) {
                return false;
            }
            send(eventName, payload);
            return true;
        }
    }

    private boolean isCurrent(Playback playback) {
        return !closed && registry.subscriberCount() > 0 && generation.get() == playback.generation();
    }

    private void waitForAudio(Playback playback, long remainingMillis) throws InterruptedException {
        long remaining = Math.max(remainingMillis, 0);
        while (remaining > 0 && isCurrent(playback)) {
            long slice = Math.min(remaining, 100);
            TimeUnit.MILLISECONDS.sleep(slice);
            remaining -= slice;
        }
    }

    private record Playback(long generation, ReplyCandidateResponse candidate) {
    }
}
