package com.bilibili.ailive;

import com.bilibili.ailive.conversation.ReplyRequest;
import com.bilibili.ailive.conversation.RoomConversationContextStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiLiveApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RoomConversationContextStore roomContextStore;

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("langchain4j.open-ai.chat-model.api-key", () -> "");
        registry.add("langchain4j.open-ai.chat-model.base-url", () -> "");
        registry.add("langchain4j.open-ai.chat-model.model-name", () -> "");
    }

    @Test
    void contextLoadsWithRealPostgresAndRedis() {
        assertEquals(
                0L,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM runtime_control_events", Long.class)
        );
        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'reply_candidates' AND column_name = 'danmaku_status'",
                        Long.class
                )
        );
        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'runtime_control_events' AND column_name = 'output_mode'",
                        Long.class
                )
        );
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }

    @Test
    void roomContextLuaMaintainsABoundedAtomicWindowInRealRedis() {
        Set<String> oldKeys = redisTemplate.keys("ai-live:room-context:*");
        if (oldKeys != null && !oldKeys.isEmpty()) {
            redisTemplate.delete(oldKeys);
        }
        Instant base = Instant.parse("2026-08-10T00:00:00Z");
        for (int index = 0; index < 15; index++) {
            roomContextStore.observe(new ReplyRequest(
                    "BILIBILI", "integration-room", "viewer-" + index, "观众" + index,
                    "message-" + index, "公开弹幕" + index, base.plusSeconds(index)
            ));
        }
        ReplyRequest latest = new ReplyRequest(
                "BILIBILI", "integration-room", "viewer-14", "观众14",
                "message-14", "公开弹幕14", base.plusSeconds(14)
        );
        roomContextStore.attachHostReply(latest, "这是原地更新后的回复");

        String context = roomContextStore.recentContext(latest.memoryId());
        assertFalse(context.contains("公开弹幕0"));
        assertFalse(context.contains("公开弹幕2"));
        assertTrue(context.contains("公开弹幕3"));
        assertTrue(context.contains("公开弹幕14"));
        assertEquals(1, occurrences(context, "公开弹幕14"));
        assertEquals(1, occurrences(context, "这是原地更新后的回复"));

        Set<String> keys = redisTemplate.keys("ai-live:room-context:*");
        assertEquals(2, keys == null ? 0 : keys.size());
        if (keys != null) {
            for (String key : keys) {
                Long ttlSeconds = redisTemplate.getExpire(key);
                assertTrue(ttlSeconds != null && ttlSeconds > 0);
            }
        }
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
