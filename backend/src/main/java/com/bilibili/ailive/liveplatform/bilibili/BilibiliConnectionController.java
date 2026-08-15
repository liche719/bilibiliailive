package com.bilibili.ailive.liveplatform.bilibili;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bilibili")
class BilibiliConnectionController {

    private final BilibiliLiveEventConnector connector;

    BilibiliConnectionController(BilibiliLiveEventConnector connector) {
        this.connector = connector;
    }

    @GetMapping("/status")
    BilibiliConnectionStatus status() {
        return connector.status();
    }

    @PostMapping("/connect")
    BilibiliConnectionStatus connect() {
        connector.connect();
        return connector.status();
    }

    @PostMapping("/disconnect")
    BilibiliConnectionStatus disconnect() {
        connector.disconnect();
        return connector.status();
    }
}
