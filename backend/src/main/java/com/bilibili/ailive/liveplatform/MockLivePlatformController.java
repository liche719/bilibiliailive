package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/mock/messages")
class MockLivePlatformController {

    private final LiveChatEventIngress liveChatEventIngress;

    MockLivePlatformController(LiveChatEventIngress liveChatEventIngress) {
        this.liveChatEventIngress = liveChatEventIngress;
    }

    @PostMapping
    ReplyCandidateResponse publish(@Valid @RequestBody MockMessageRequest request) {
        String senderId = request.senderId() == null || request.senderId().isBlank()
                ? "mock-viewer"
                : request.senderId();
        String senderName = request.senderName() == null || request.senderName().isBlank()
                ? senderId
                : request.senderName();
        LiveChatEvent event = new LiveChatEvent(
                LivePlatform.MOCK,
                request.roomId(),
                senderId,
                senderName,
                UUID.randomUUID().toString(),
                request.messageText(),
                Instant.now(),
                false
        );
        return liveChatEventIngress.accept(event);
    }
}
