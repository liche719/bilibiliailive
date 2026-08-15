package com.bilibili.ailive.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalOnlyServerGuardTest {

    @Test
    void acceptsLoopbackAddresses() {
        assertDoesNotThrow(() -> new LocalOnlyServerGuard("127.0.0.1").requireLoopbackAddress());
        assertDoesNotThrow(() -> new LocalOnlyServerGuard("::1").requireLoopbackAddress());
    }

    @Test
    void rejectsPublicOrLanBindingUntilAuthenticationExists() {
        assertThrows(
                IllegalStateException.class,
                () -> new LocalOnlyServerGuard("0.0.0.0").requireLoopbackAddress()
        );
    }
}
