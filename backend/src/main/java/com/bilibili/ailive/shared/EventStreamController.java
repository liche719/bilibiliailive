package com.bilibili.ailive.shared;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
class EventStreamController {

    private final SseHub sseHub;

    EventStreamController(SseHub sseHub) {
        this.sseHub = sseHub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe() {
        return sseHub.subscribe();
    }
}
