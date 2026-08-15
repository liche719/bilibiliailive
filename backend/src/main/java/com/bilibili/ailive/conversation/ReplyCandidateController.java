package com.bilibili.ailive.conversation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/replies")
class ReplyCandidateController {

    private final ReplyWorkflowService replyWorkflowService;

    ReplyCandidateController(ReplyWorkflowService replyWorkflowService) {
        this.replyWorkflowService = replyWorkflowService;
    }

    @GetMapping
    List<ReplyCandidateResponse> list() {
        return replyWorkflowService.listCandidates();
    }

}
