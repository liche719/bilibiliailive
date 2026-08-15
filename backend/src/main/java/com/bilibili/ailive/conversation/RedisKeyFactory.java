package com.bilibili.ailive.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RedisKeyFactory {

    private RedisKeyFactory() {
    }

    static String opaqueKey(String prefix, String scope, String... identityParts) {
        String identity = String.join("\u001f", identityParts);
        return prefix + ":" + scope + ":" + sha256(identity);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
