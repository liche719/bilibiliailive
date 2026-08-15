package com.bilibili.ailive.shared;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseHub {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    public SseEmitter subscribe() {
        return registry.subscribe();
    }

    public void publish(String eventName, Object payload) {
        registry.send(eventName, payload);
    }

    @Scheduled(fixedDelayString = "${app.sse.heartbeat-interval:PT15S}")
    void heartbeat() {
        registry.heartbeat();
    }

    public int subscriberCount() {
        return registry.subscriberCount();
    }
}
