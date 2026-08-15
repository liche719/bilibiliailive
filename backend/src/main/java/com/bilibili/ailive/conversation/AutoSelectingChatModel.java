package com.bilibili.ailive.conversation;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class AutoSelectingChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(AutoSelectingChatModel.class);

    private final ChatModel responsesModel;
    private final ChatModel chatCompletionsModel;
    private final LiveModelRuntimeState runtimeState;
    private final AtomicReference<ChatModel> selectedModel = new AtomicReference<>();
    private final Object selectionLock = new Object();

    AutoSelectingChatModel(
            ChatModel responsesModel,
            ChatModel chatCompletionsModel,
            LiveModelRuntimeState runtimeState
    ) {
        this.responsesModel = Objects.requireNonNull(responsesModel, "responsesModel");
        this.chatCompletionsModel = Objects.requireNonNull(chatCompletionsModel, "chatCompletionsModel");
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return chat(chatRequest, ChatRequestOptions.EMPTY);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest, ChatRequestOptions options) {
        ChatModel model = selectedModel.get();
        if (model != null) {
            return chatWithSelectedModel(model, chatRequest, options);
        }
        synchronized (selectionLock) {
            model = selectedModel.get();
            if (model != null) {
                return model.chat(chatRequest, options);
            }
            return selectAndChat(chatRequest, options);
        }
    }

    private ChatResponse chatWithSelectedModel(
            ChatModel model,
            ChatRequest chatRequest,
            ChatRequestOptions options
    ) {
        try {
            return model.chat(chatRequest, options);
        } catch (RuntimeException selectedFailure) {
            if (!ModelFailureClassifier.unsupportedEndpoint(selectedFailure)) {
                throw selectedFailure;
            }
            synchronized (selectionLock) {
                ChatModel current = selectedModel.get();
                if (current != model) {
                    return current == null
                            ? selectAndChat(chatRequest, options)
                            : current.chat(chatRequest, options);
                }
                ChatModel alternative = model == responsesModel ? chatCompletionsModel : responsesModel;
                try {
                    ChatResponse response = alternative.chat(chatRequest, options);
                    selectedModel.set(alternative);
                    recordSelection(alternative);
                    logger.info("Previously selected OpenAI protocol became unsupported; switched live chat protocol");
                    return response;
                } catch (RuntimeException alternativeFailure) {
                    alternativeFailure.addSuppressed(selectedFailure);
                    throw alternativeFailure;
                }
            }
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        return chat(chatRequest);
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OPEN_AI;
    }

    private ChatResponse selectAndChat(ChatRequest chatRequest, ChatRequestOptions options) {
        try {
            ChatResponse response = responsesModel.chat(chatRequest, options);
            selectedModel.set(responsesModel);
            recordSelection(responsesModel);
            logger.info("Selected OpenAI Responses API for the live chat model");
            return response;
        } catch (RuntimeException responsesFailure) {
            if (!ModelFailureClassifier.unsupportedEndpoint(responsesFailure)) {
                throw responsesFailure;
            }
            logger.info("Responses endpoint is unsupported; selecting Chat Completions for the live chat model");
            try {
                ChatResponse response = chatCompletionsModel.chat(chatRequest, options);
                selectedModel.set(chatCompletionsModel);
                recordSelection(chatCompletionsModel);
                return response;
            } catch (RuntimeException chatCompletionsFailure) {
                chatCompletionsFailure.addSuppressed(responsesFailure);
                throw chatCompletionsFailure;
            }
        }
    }

    private void recordSelection(ChatModel model) {
        runtimeState.selected(model == responsesModel
                ? LiveModelRuntimeState.ActiveApiMode.RESPONSES
                : LiveModelRuntimeState.ActiveApiMode.CHAT_COMPLETIONS);
    }

}
