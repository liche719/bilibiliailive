package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.conversation.ReplyCandidateResponse;

public interface LiveChatEventIngress {

    ReplyCandidateResponse accept(LiveChatEvent event);
}
