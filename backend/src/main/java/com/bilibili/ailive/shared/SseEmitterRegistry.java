package com.bilibili.ailive.shared;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SseEmitterRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception exception) {
            remove.run();
        }
        return emitter;
    }

    public void send(String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception exception) {
                emitters.remove(emitter);
            }
        }
    }

    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception exception) {
                emitters.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
