package com.bilibili.ailive.conversation;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@EnabledIfSystemProperty(named = "remote-ai-test", matches = "true")
class RemoteResponsesToolCallingTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private StreamHostAssistant assistant;

    @Autowired
    private RoomConversationContextStore roomContextStore;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.live-platform.bilibili.open-live-enabled", () -> false);
    }

    @Test
    void returnsStructuredReplyAndCanUseRoomContextTool() {
        LiveHostReply directReply = assistant.reply(
                "BILIBILI:remote-room:viewer-a",
                LiveHostProfileSnapshot.defaults("remote-room").systemPromptSection(),
                "当前发言观众：小明\n当前弹幕：你好，请简短打个招呼"
        );
        assertNotNull(directReply);
        assertNotNull(directReply.overlayText());

        roomContextStore.observe(new ReplyRequest(
                "BILIBILI", "remote-room", "viewer-a", "小明", "remote-message-1",
                "我觉得鸡蛋适量吃对身体不错", Instant.now().minusSeconds(1)
        ));
        double callsBefore = meterRegistry.get("ai.live.tools.room-context").timer().count();
        LiveHostReply contextualReply = assistant.reply(
                "BILIBILI:remote-room:viewer-b",
                LiveHostProfileSnapshot.defaults("remote-room").systemPromptSection(),
                "当前发言观众：小红\n当前弹幕：刚刚小明关于鸡蛋的说法有道理吗？"
        );

        assertNotNull(contextualReply);
        assertNotNull(contextualReply.overlayText());
        assertTrue(meterRegistry.get("ai.live.tools.room-context").timer().count() > callsBefore);
    }
}
