package com.bilibili.ailive.conversation;

final class ModelCircuitOpenException extends RuntimeException {

    ModelCircuitOpenException() {
        super("Model circuit is temporarily open");
    }
}
