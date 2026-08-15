package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.conversation.ReplyWorkflowService;
import com.bilibili.ailive.conversation.ReplyRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReplyWorkflowLiveChatEventIngressTest {

    @Test
    void treatsPrefixedBroadcasterMessageAsManualTestViewerInput() {
        ReplyWorkflowService replyWorkflowService = mock(ReplyWorkflowService.class);
        OutboundDanmakuEchoGuard echoGuard = mock(OutboundDanmakuEchoGuard.class);
        ReplyWorkflowLiveChatEventIngress ingress = new ReplyWorkflowLiveChatEventIngress(
                replyWorkflowService,
                echoGuard
        );
        LiveChatEvent event = new LiveChatEvent(
                LivePlatform.BILIBILI,
                "1000",
                "broadcaster",
                "Anchor",
                "message-test",
                "0\uFF1A hello",
                Instant.now(),
                true
        );

        ingress.accept(event);

        verify(replyWorkflowService).createCandidate(new ReplyRequest(
                "BILIBILI",
                "1000",
                "broadcaster:manual-test-viewer",
                "Anchor",
                "message-test",
                "hello",
                event.occurredAt()
        ));
        org.mockito.Mockito.verify(replyWorkflowService, org.mockito.Mockito.never())
                .recordIgnoredEcho(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forwardsNormalizedEventsToTheReplyWorkflow() {
        ReplyWorkflowService replyWorkflowService = mock(ReplyWorkflowService.class);
        ReplyWorkflowLiveChatEventIngress ingress = new ReplyWorkflowLiveChatEventIngress(
                replyWorkflowService,
                mock(OutboundDanmakuEchoGuard.class)
        );
        LiveChatEvent event = new LiveChatEvent(
                LivePlatform.MOCK,
                "1000",
                "viewer-1",
                "message-1",
                "你好",
                Instant.now()
        );

        ingress.accept(event);

        verify(replyWorkflowService).createCandidate(new ReplyRequest(
                "MOCK",
                "1000",
                "viewer-1",
                "message-1",
                "你好",
                event.occurredAt()
        ));
    }

    @Test
    void recordsBroadcasterEchoWithoutStartingAnotherReply() {
        ReplyWorkflowService replyWorkflowService = mock(ReplyWorkflowService.class);
        OutboundDanmakuEchoGuard echoGuard = mock(OutboundDanmakuEchoGuard.class);
        ReplyWorkflowLiveChatEventIngress ingress = new ReplyWorkflowLiveChatEventIngress(
                replyWorkflowService,
                echoGuard
        );
        LiveChatEvent event = new LiveChatEvent(
                LivePlatform.BILIBILI,
                "1000",
                "broadcaster",
                "message-1",
                "欢迎来到直播间～",
                Instant.now(),
                true
        );
        org.mockito.Mockito.when(echoGuard.isEcho(event)).thenReturn(true);

        ingress.accept(event);

        verify(replyWorkflowService).recordIgnoredEcho(
                new ReplyRequest(
                        "BILIBILI",
                        "1000",
                        "broadcaster",
                        "message-1",
                        "欢迎来到直播间～",
                        event.occurredAt()
                ),
                "已忽略 AI 自己发送后回流的弹幕"
        );
        org.mockito.Mockito.verify(replyWorkflowService, org.mockito.Mockito.never()).createCandidate(org.mockito.ArgumentMatchers.any());
    }
}
