package com.bilibili.ailive.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSearchToolsTest {

    @Test
    void reportsThatSearchIsUnavailableUntilExplicitlyConfigured() {
        WebSearchProperties properties = new WebSearchProperties(
                false, URI.create("http://localhost:8081/search"), 3,
                Duration.ofSeconds(8), 200, 3000
        );
        WebSearchTools tools = new WebSearchTools(
                properties, new ObjectMapper(), HttpClient.newHttpClient(), new LiveReplyMetrics(new SimpleMeterRegistry())
        );

        assertEquals(
                "联网搜索未启用或暂时不可用；请勿编造实时信息。",
                tools.searchPublicWeb("今天的新闻", "MOCK:room:viewer")
        );
    }
}
