package com.bilibili.ailive.conversation;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

abstract class ForwardingStreamingChatResponseHandler implements StreamingChatResponseHandler {

    protected final StreamingChatResponseHandler delegate;

    ForwardingStreamingChatResponseHandler(StreamingChatResponseHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        delegate.onPartialResponse(partialResponse);
    }

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        delegate.onPartialResponse(partialResponse, context);
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking) {
        delegate.onPartialThinking(partialThinking);
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        delegate.onPartialThinking(partialThinking, context);
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall) {
        delegate.onPartialToolCall(partialToolCall);
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        delegate.onPartialToolCall(partialToolCall, context);
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        delegate.onCompleteToolCall(completeToolCall);
    }

    @Override
    public void onUnmappedRawEvent(Object rawEvent) {
        delegate.onUnmappedRawEvent(rawEvent);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        delegate.onCompleteResponse(completeResponse);
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }
}
