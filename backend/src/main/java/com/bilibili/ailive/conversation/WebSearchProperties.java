package com.bilibili.ailive.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.web-search")
record WebSearchProperties(
        boolean enabled,
        URI endpoint,
        int maxResults,
        Duration timeout,
        int maxQueryCharacters,
        int maxResultCharacters
) {

    WebSearchProperties {
        endpoint = endpoint == null ? URI.create("http://localhost:8081/search") : endpoint;
        maxResults = Math.clamp(maxResults, 1, 5);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(8) : timeout;
        maxQueryCharacters = Math.clamp(maxQueryCharacters, 20, 500);
        maxResultCharacters = Math.clamp(maxResultCharacters, 500, 10_000);
    }

    boolean isConfigured() {
        return enabled;
    }
}
