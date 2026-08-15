package com.bilibili.ailive.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisconnectedClientExceptionHandlerTest {

    private final DisconnectedClientExceptionHandler handler = new DisconnectedClientExceptionHandler();

    @Test
    void ignoresLocalizedWindowsClientDisconnects() {
        IOException exception = new IOException("你的主机中的软件中止了一个已建立的连接。");

        assertDoesNotThrow(() -> handler.handle(exception));
    }

    @Test
    void ignoresEnglishWindowsClientDisconnectsInTheCauseChain() {
        IOException exception = new IOException(
                "SSE write failed",
                new IOException("An established connection was aborted by the software in your host machine")
        );

        assertDoesNotThrow(() -> handler.handle(exception));
    }

    @Test
    void rethrowsUnrelatedIoFailures() {
        IOException exception = new IOException("Unable to serialize response payload");

        IOException thrown = assertThrows(IOException.class, () -> handler.handle(exception));

        assertSame(exception, thrown);
    }
}
