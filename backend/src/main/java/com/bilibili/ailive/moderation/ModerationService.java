package com.bilibili.ailive.moderation;

public interface ModerationService {

    ModerationOutcome evaluateInput(String messageText);

    ModerationOutcome evaluateOutput(String replyText);
}
