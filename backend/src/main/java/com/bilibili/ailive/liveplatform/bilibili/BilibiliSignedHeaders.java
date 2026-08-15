package com.bilibili.ailive.liveplatform.bilibili;

record BilibiliSignedHeaders(
        String contentMd5,
        String timestamp,
        String nonce,
        String authorization
) {
}
