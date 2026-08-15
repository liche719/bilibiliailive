package com.bilibili.ailive.conversation;

class RoomQueueFullException extends RuntimeException {

    RoomQueueFullException() {
        super("The live room reply queue is full");
    }
}
