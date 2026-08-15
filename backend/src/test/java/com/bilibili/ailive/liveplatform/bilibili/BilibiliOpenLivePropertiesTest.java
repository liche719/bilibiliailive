package com.bilibili.ailive.liveplatform.bilibili;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliOpenLivePropertiesTest {

    @Test
    void reportsConfiguredOnlyWhenEnabledAndAllCredentialsExist() {
        assertTrue(BilibiliRequestSignerTest.properties().isConfigured());

        BilibiliOpenLiveProperties disabled = properties(false, "key", "secret", 123L, "code");
        BilibiliOpenLiveProperties missingIdentityCode = properties(true, "key", "secret", 123L, " ");

        assertFalse(disabled.isConfigured());
        assertFalse(missingIdentityCode.isConfigured());
        assertThrows(IllegalStateException.class, missingIdentityCode::requireConfigured);
    }

    @Test
    void rejectsUnsafeExecutorAndHeartbeatLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BilibiliOpenLiveProperties(
                        true,
                        false,
                        URI.create("https://live-open.biliapi.com"),
                        "key",
                        "secret",
                        123L,
                        "code",
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(5),
                        6,
                        0,
                        4,
                        100
                )
        );
    }

    private static BilibiliOpenLiveProperties properties(
            boolean enabled,
            String accessKeyId,
            String accessKeySecret,
            long appId,
            String identityCode
    ) {
        return new BilibiliOpenLiveProperties(
                enabled,
                false,
                URI.create("https://live-open.biliapi.com"),
                accessKeyId,
                accessKeySecret,
                appId,
                identityCode,
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                Duration.ofSeconds(5),
                6,
                3,
                4,
                100
        );
    }
}
