package com.bilibili.ailive.overlay;

import com.bilibili.ailive.conversation.ReplyWorkflowService;
import com.bilibili.ailive.runtime.RuntimeControlService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverlayControllerTest {

    @Test
    void returnsAnEmptyOverlayWhileAutomaticRepliesArePaused() {
        ReplyWorkflowService replyWorkflowService = mock(ReplyWorkflowService.class);
        RuntimeControlService runtimeControlService = mock(RuntimeControlService.class);
        when(runtimeControlService.isPaused()).thenReturn(true);
        OverlayController controller = new OverlayController(
                replyWorkflowService,
                mock(OverlayHub.class),
                runtimeControlService
        );

        assertNull(controller.current());
        verify(replyWorkflowService, never()).currentPublishedReply();
    }

    @Test
    void returnsNoRecentRepliesWhileAutomaticRepliesArePaused() {
        ReplyWorkflowService replyWorkflowService = mock(ReplyWorkflowService.class);
        RuntimeControlService runtimeControlService = mock(RuntimeControlService.class);
        when(runtimeControlService.isPaused()).thenReturn(true);
        OverlayController controller = new OverlayController(
                replyWorkflowService,
                mock(OverlayHub.class),
                runtimeControlService
        );

        assertEquals(List.of(), controller.recent());
        verify(replyWorkflowService, never()).recentPublishedReplies();
    }
}
