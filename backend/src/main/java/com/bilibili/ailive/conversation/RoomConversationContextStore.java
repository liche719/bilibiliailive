package com.bilibili.ailive.conversation;

public interface RoomConversationContextStore {

    String recentContext(String memoryId);

    void observe(ReplyRequest request);

    void attachHostReply(ReplyRequest request, String hostReply);
}
