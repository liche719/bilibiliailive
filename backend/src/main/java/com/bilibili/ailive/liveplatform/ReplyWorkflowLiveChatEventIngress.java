package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;
import com.bilibili.ailive.conversation.ReplyRequest;
import com.bilibili.ailive.conversation.ReplyWorkflowService;
import org.springframework.stereotype.Component;

@Component
class ReplyWorkflowLiveChatEventIngress implements LiveChatEventIngress {

    private static final String FULL_WIDTH_TEST_PREFIX = "0\uFF1A";
    private static final String ASCII_TEST_PREFIX = "0:";
    private static final String TEST_VIEWER_SUFFIX = ":manual-test-viewer";

    private final ReplyWorkflowService replyWorkflowService;
    private final OutboundDanmakuEchoGuard echoGuard;

    ReplyWorkflowLiveChatEventIngress(
            ReplyWorkflowService replyWorkflowService,
            OutboundDanmakuEchoGuard echoGuard
    ) {
        this.replyWorkflowService = replyWorkflowService;
        this.echoGuard = echoGuard;
    }

    @Override
    public ReplyCandidateResponse accept(LiveChatEvent event) {
        ReplyRequest request = new ReplyRequest(
                event.platform().name(),
                event.roomId(),
                event.senderId(),
                event.senderName(),
                event.messageId(),
                event.messageText(),
                event.occurredAt()
        );
        String manualTestMessage = manualTestMessage(event.messageText());
        if (event.broadcasterMessage()
                && manualTestMessage != null
                && !echoGuard.isEcho(event)) {
            ReplyRequest manualTestRequest = new ReplyRequest(
                    event.platform().name(),
                    event.roomId(),
                    event.senderId() + TEST_VIEWER_SUFFIX,
                    event.senderName(),
                    event.messageId(),
                    manualTestMessage,
                    event.occurredAt()
            );
            return replyWorkflowService.createCandidate(manualTestRequest);
        }
        if (event.broadcasterMessage()) {
            String reason = echoGuard.isEcho(event)
                    ? "已忽略 AI 自己发送后回流的弹幕"
                    : "已忽略主播账号发送的消息";
            return replyWorkflowService.recordIgnoredEcho(request, reason);
        }
        return replyWorkflowService.createCandidate(request);
    }

    private static String manualTestMessage(String messageText) {
        String prefix = messageText.startsWith(FULL_WIDTH_TEST_PREFIX)
                ? FULL_WIDTH_TEST_PREFIX
                : messageText.startsWith(ASCII_TEST_PREFIX) ? ASCII_TEST_PREFIX : null;
        if (prefix == null) {
            return null;
        }
        String content = messageText.substring(prefix.length()).strip();
        return content.isEmpty() ? null : content;
    }
}
