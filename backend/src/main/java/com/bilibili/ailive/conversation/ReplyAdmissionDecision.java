package com.bilibili.ailive.conversation;

record ReplyAdmissionDecision(boolean allowed, String reason) {

    static ReplyAdmissionDecision allow() {
        return new ReplyAdmissionDecision(true, null);
    }

    static ReplyAdmissionDecision reject(String reason) {
        return new ReplyAdmissionDecision(false, reason);
    }
}
