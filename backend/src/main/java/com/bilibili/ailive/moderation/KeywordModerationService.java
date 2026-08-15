package com.bilibili.ailive.moderation;

import com.bilibili.ailive.conversation.LiveReplyPolicyProperties;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class KeywordModerationService implements ModerationService {

    private static final List<String> BLOCKED_PHRASES = List.of("自杀", "炸弹", "仇恨");

    private final LiveReplyPolicyProperties properties;

    KeywordModerationService(LiveReplyPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public ModerationOutcome evaluateInput(String messageText) {
        boolean hasBlockedPhrase = BLOCKED_PHRASES.stream().anyMatch(messageText::contains);
        return hasBlockedPhrase ? ModerationOutcome.block("消息命中本地安全规则") : ModerationOutcome.allow();
    }

    @Override
    public ModerationOutcome evaluateOutput(String replyText) {
        int characterCount = replyText.codePointCount(0, replyText.length());
        if (characterCount > properties.maxReplyCharacters()) {
            return ModerationOutcome.block("模型回复超过自动上屏长度限制");
        }
        boolean hasBlockedPhrase = BLOCKED_PHRASES.stream().anyMatch(replyText::contains);
        return hasBlockedPhrase ? ModerationOutcome.block("模型回复命中本地安全规则") : ModerationOutcome.allow();
    }
}
