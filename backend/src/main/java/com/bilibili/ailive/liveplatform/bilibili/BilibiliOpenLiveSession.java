package com.bilibili.ailive.liveplatform.bilibili;

import java.util.List;

record BilibiliOpenLiveSession(
        String gameId,
        String authBody,
        List<String> websocketUrls,
        long roomId,
        String anchorOpenId
) {
}
