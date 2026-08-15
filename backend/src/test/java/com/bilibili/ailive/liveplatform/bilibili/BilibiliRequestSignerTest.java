package com.bilibili.ailive.liveplatform.bilibili;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BilibiliRequestSignerTest {

    @Test
    void generatesTheDocumentedCanonicalHeaderSignature() {
        BilibiliOpenLiveProperties properties = properties();
        BilibiliRequestSigner signer = new BilibiliRequestSigner(
                properties,
                Clock.fixed(Instant.ofEpochSecond(1624594467), ZoneOffset.UTC),
                () -> "ad184c09-095f-91c3-0849-230dd3744045"
        );

        BilibiliSignedHeaders headers = signer.sign(
                "{\"app_id\":123,\"code\":\"test\"}".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals("58c5a1666cdbc39dcc0070c65773ce0f", headers.contentMd5());
        assertEquals("1624594467", headers.timestamp());
        assertEquals("ad184c09-095f-91c3-0849-230dd3744045", headers.nonce());
        assertEquals("c16076160cc510fc92204ee97a0239f6dcf376fb681fcbdcf51e966acfa98c1c", headers.authorization());
    }

    static BilibiliOpenLiveProperties properties() {
        return new BilibiliOpenLiveProperties(
                true,
                false,
                URI.create("https://live-open.biliapi.com"),
                "xxxx",
                "JzOzZfSHeYYnAMZ",
                123L,
                "identity-code",
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
