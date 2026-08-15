package com.bilibili.ailive.moderation;

import com.bilibili.ailive.conversation.LiveReplyPolicyProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordModerationServiceTest {

    private final KeywordModerationService service = new KeywordModerationService(
            new LiveReplyPolicyProperties(
                    10,
                    Duration.ofSeconds(3),
                    20,
                    Duration.ofMinutes(1),
                    4,
                    2,
                    100,
                    Duration.ofSeconds(5),
                    4,
                    "test:reply-policy"
            )
    );

    @Test
    void blocksUnsafeModelOutput() {
        assertFalse(service.evaluateOutput("讨论炸弹制作").allowed());
    }

    @Test
    void blocksModelOutputBeyondTheDisplayLimit() {
        assertFalse(service.evaluateOutput("这是一段明显超过限制的模型回复").allowed());
    }

    @Test
    void allowsShortSafeModelOutput() {
        assertTrue(service.evaluateOutput("晚上好呀").allowed());
    }
}
