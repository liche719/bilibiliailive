package com.bilibili.ailive.conversation;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class AutoSelectingStreamingChatModel implements StreamingChatModel {

    private static final Logger logger = LoggerFactory.getLogger(AutoSelectingStreamingChatModel.class);
    private final StreamingChatModel responsesModel;
    private final StreamingChatModel chatCompletionsModel;
    private final LiveModelRuntimeState runtimeState;
    private final AtomicReference<StreamingChatModel> selectedModel = new AtomicReference<>();

    AutoSelectingStreamingChatModel(
            StreamingChatModel responsesModel,
            StreamingChatModel chatCompletionsModel,
            LiveModelRuntimeState runtimeState
    ) {
        this.responsesModel = Objects.requireNonNull(responsesModel, "responsesModel");
        this.chatCompletionsModel = Objects.requireNonNull(chatCompletionsModel, "chatCompletionsModel");
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        StreamingChatModel selected = selectedModel.get();
        invoke(selected == null ? responsesModel : selected, request, handler, true);
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OPEN_AI;
    }

    private void invoke(
            StreamingChatModel model,
            ChatRequest request,
            StreamingChatResponseHandler handler,
            boolean allowFallback
    ) {
        AtomicBoolean emitted = new AtomicBoolean();
        try {
            model.chat(request, new ForwardingStreamingChatResponseHandler(handler) {
                @Override
                public void onPartialResponse(String partialResponse) {
                    emitted.set(true);
                    super.onPartialResponse(partialResponse);
                }

                @Override
                public void onPartialResponse(
                        PartialResponse partialResponse,
                        PartialResponseContext context
                ) {
                    emitted.set(true);
                    super.onPartialResponse(partialResponse, context);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    emitted.set(true);
                    super.onPartialThinking(partialThinking);
                }

                @Override
                public void onPartialThinking(
                        PartialThinking partialThinking,
                        PartialThinkingContext context
                ) {
                    emitted.set(true);
                    super.onPartialThinking(partialThinking, context);
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    emitted.set(true);
                    super.onPartialToolCall(partialToolCall);
                }

                @Override
                public void onPartialToolCall(
                        PartialToolCall partialToolCall,
                        PartialToolCallContext context
                ) {
                    emitted.set(true);
                    super.onPartialToolCall(partialToolCall, context);
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    emitted.set(true);
                    super.onCompleteToolCall(completeToolCall);
                }

                @Override
                public void onUnmappedRawEvent(Object rawEvent) {
                    emitted.set(true);
                    super.onUnmappedRawEvent(rawEvent);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    select(model);
                    delegate.onCompleteResponse(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    if (emitted.get()
                            || !allowFallback
                            || !ModelFailureClassifier.unsupportedEndpoint(error)) {
                        delegate.onError(error);
                        return;
                    }
                    StreamingChatModel alternative = model == responsesModel
                            ? chatCompletionsModel
                            : responsesModel;
                    logger.info("Streaming OpenAI protocol is unsupported; trying the alternative protocol");
                    invoke(alternative, request, delegate, false);
                }
            });
        } catch (RuntimeException failure) {
            handler.onError(failure);
        }
    }

    private void select(StreamingChatModel model) {
        StreamingChatModel previous = selectedModel.getAndSet(model);
        runtimeState.selected(model == responsesModel
                ? LiveModelRuntimeState.ActiveApiMode.RESPONSES
                : LiveModelRuntimeState.ActiveApiMode.CHAT_COMPLETIONS);
        if (previous == null) {
            logger.info("Selected {} for streaming live replies",
                    model == responsesModel ? "OpenAI Responses API" : "OpenAI Chat Completions");
        } else if (previous != model) {
            logger.info("Switched the streaming live reply protocol");
        }
    }
}
