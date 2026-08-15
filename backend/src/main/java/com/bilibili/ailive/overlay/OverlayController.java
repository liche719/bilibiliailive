package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;
import com.bilibili.ailive.conversation.ReplyWorkflowService;
import com.bilibili.ailive.runtime.RuntimeControlService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/overlay")
class OverlayController {

    private final ReplyWorkflowService replyWorkflowService;
    private final OverlayHub overlayHub;
    private final RuntimeControlService runtimeControlService;

    OverlayController(
            ReplyWorkflowService replyWorkflowService,
            OverlayHub overlayHub,
            RuntimeControlService runtimeControlService
    ) {
        this.replyWorkflowService = replyWorkflowService;
        this.overlayHub = overlayHub;
        this.runtimeControlService = runtimeControlService;
    }

    @GetMapping("/current")
    ReplyCandidateResponse current() {
        return runtimeControlService.isPaused() ? null : replyWorkflowService.currentPublishedReply();
    }

    @GetMapping("/recent")
    List<ReplyCandidateResponse> recent() {
        return runtimeControlService.isPaused() ? List.of() : replyWorkflowService.recentPublishedReplies();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return overlayHub.subscribe();
    }
}
