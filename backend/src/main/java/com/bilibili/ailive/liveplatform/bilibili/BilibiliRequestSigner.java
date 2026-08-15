package com.bilibili.ailive.liveplatform.bilibili;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Component
class BilibiliRequestSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final BilibiliOpenLiveProperties properties;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    @Autowired
    BilibiliRequestSigner(BilibiliOpenLiveProperties properties) {
        this(properties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    BilibiliRequestSigner(
            BilibiliOpenLiveProperties properties,
            Clock clock,
            Supplier<String> nonceSupplier
    ) {
        this.properties = properties;
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    BilibiliSignedHeaders sign(byte[] requestBody) {
        properties.requireConfigured();
        String contentMd5 = digestHex("MD5", requestBody);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = nonceSupplier.get();
        String canonicalHeaders = String.join(
                "\n",
                "x-bili-accesskeyid:" + properties.accessKeyId(),
                "x-bili-content-md5:" + contentMd5,
                "x-bili-signature-method:HMAC-SHA256",
                "x-bili-signature-nonce:" + nonce,
                "x-bili-signature-version:1.0",
                "x-bili-timestamp:" + timestamp
        );
        return new BilibiliSignedHeaders(
                contentMd5,
                timestamp,
                nonce,
                hmacHex(properties.accessKeySecret(), canonicalHeaders)
        );
    }

    private static String digestHex(String algorithm, byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate Bilibili request signature", exception);
        }
    }
}
