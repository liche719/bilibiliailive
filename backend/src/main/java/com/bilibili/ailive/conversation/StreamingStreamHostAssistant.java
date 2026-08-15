package com.bilibili.ailive.conversation;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        streamingChatModel = "liveStreamingChatModel",
        chatMemoryProvider = "liveHostChatMemoryProvider",
        tools = {"roomContextTools", "webSearchTools"}
)
interface StreamingStreamHostAssistant extends ChatMemoryAccess {

    @SystemMessage(fromResource = "prompts/live-host/system-message.txt")
    @UserMessage(fromResource = "prompts/live-host/reply-user-message.txt")
    TokenStream reply(
            @MemoryId String memoryId,
            @V("hostProfile") String hostProfile,
            @V("viewerInput") String viewerInput
    );
}
