package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;

interface ViewerWelcomeAdmission {

    boolean admit(ViewerEnteredEvent event, String sessionId);
}
