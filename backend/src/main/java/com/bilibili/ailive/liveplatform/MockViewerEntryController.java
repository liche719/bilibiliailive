package com.bilibili.ailive.liveplatform;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/mock/audience/entries")
class MockViewerEntryController {

    private final ViewerEnteredEventIngress eventIngress;

    MockViewerEntryController(ViewerEnteredEventIngress eventIngress) {
        this.eventIngress = eventIngress;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ViewerEnteredEvent publish(@Valid @RequestBody MockViewerEntryRequest request) {
        ViewerEnteredEvent event = new ViewerEnteredEvent(
                LivePlatform.MOCK,
                request.roomId(),
                request.viewerId(),
                request.viewerName(),
                Instant.now()
        );
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "mock-session"
                : request.sessionId();
        eventIngress.accept(event, sessionId);
        return event;
    }
}
