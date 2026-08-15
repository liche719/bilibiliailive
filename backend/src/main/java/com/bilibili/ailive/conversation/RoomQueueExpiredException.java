package com.bilibili.ailive.conversation;

class RoomQueueExpiredException extends RuntimeException {

    RoomQueueExpiredException() {
        super("The live room reply expired while waiting in the queue");
    }
}
