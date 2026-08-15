package com.bilibili.ailive.shared;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RestControllerAdvice
class DisconnectedClientExceptionHandler {

    private static final List<String> WINDOWS_DISCONNECT_MESSAGES = List.of(
            "你的主机中的软件中止了一个已建立的连接",
            "远程主机强迫关闭了一个现有的连接",
            "an established connection was aborted by the software in your host machine",
            "an existing connection was forcibly closed by the remote host"
    );

    @ExceptionHandler(IOException.class)
    void handle(IOException exception) throws IOException {
        if (!isDisconnectedClient(exception)) {
            throw exception;
        }
    }

    private boolean isDisconnectedClient(IOException exception) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            return true;
        }
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (WINDOWS_DISCONNECT_MESSAGES.stream().anyMatch(normalized::contains)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
