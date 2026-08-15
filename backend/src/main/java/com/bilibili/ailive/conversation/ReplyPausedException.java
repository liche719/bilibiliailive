package com.bilibili.ailive.conversation;

class ReplyPausedException extends RuntimeException {

    ReplyPausedException() {
        super("Automatic replies are paused");
    }
}
