package com.bilibili.ailive.conversation;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import com.bilibili.ailive.runtime.RuntimeControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveHostConversationServiceTest {

    @Test
    void isolatesMemoriesByPlatformRoomAndSender() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        LiveHostConversationService service = service(assistant, 10);
        ReplyRequest firstViewer = request("viewer-1", "message-1", "你好");
        ReplyRequest secondViewer = request("viewer-2", "message-2", "晚上好");
        when(assistant.reply(eq(firstViewer.memoryId()), anyString(), anyString())).thenReturn(reply("你好呀"));
        when(assistant.reply(eq(secondViewer.memoryId()), anyString(), anyString())).thenReturn(reply("晚上好"));

        assertEquals("你好呀", service.reply(firstViewer).overlayText());
        assertEquals("晚上好", service.reply(secondViewer).overlayText());

        verify(assistant).reply(eq("MOCK:1000:viewer-1"), anyString(), argThat(input -> input.contains("当前弹幕：你好")));
        verify(assistant).reply(eq("MOCK:1000:viewer-2"), anyString(), argThat(input -> input.contains("当前弹幕：晚上好")));
    }

    @Test
    void injectsTheSelectedRoomProfileIntoTheModelRequest() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        LiveHostConversationService service = service(assistant, 10);
        ReplyRequest request = request("viewer-1", "message-1", "今天聊什么？");
        LiveHostProfileSnapshot profile = new LiveHostProfileSnapshot(
                "1000",
                "小航",
                "温和、有耐心的科技主播",
                "Java 与 AI",
                "自然、简短",
                100,
                "",
                "欢迎来到直播间",
                true,
                3,
                Instant.parse("2026-08-10T00:00:00Z")
        );
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString()))
                .thenReturn(reply("今晚聊 Java 与 AI"));

        LiveHostReply generated = service.replyStreaming(request, profile);

        assertEquals("今晚聊 Java 与 AI", generated.overlayText());
        verify(assistant).reply(
                eq(request.memoryId()),
                argThat(prompt -> prompt.contains("小航") && prompt.contains("Java 与 AI")),
                argThat(input -> input.contains("今天聊什么？"))
        );
    }

    @Test
    void injectsTrustedChinaDateAndWeekdayIntoTheModelRequest() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        ReplyRequest request = request("viewer-1", "message-date", "今天是星期几？");
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString())).thenReturn(reply("今天是星期一。"));
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runningControl(),
                passthroughMetrics(),
                10,
                Duration.ofMinutes(30),
                Clock.fixed(Instant.parse("2026-08-09T16:30:00Z"), ZoneOffset.UTC)
        );

        service.reply(request);

        verify(assistant).reply(
                eq(request.memoryId()),
                anyString(),
                argThat(input -> input.contains("当前日期时间（中国标准时间，可信系统时间）：2026年08月10日 00:30:00，星期一"))
        );
    }

    @Test
    void doesNotInjectSharedRoomContextIntoOrdinaryModelInput() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        ReplyRequest request = request("viewer-2", "message-2", "刚刚那个人说得对吗？");
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString()))
                .thenReturn(reply("小明的说法大体有道理。"));
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runningControl(),
                passthroughMetrics(),
                10,
                Duration.ofMinutes(30),
                Clock.systemUTC()
        );

        assertEquals("小明的说法大体有道理。", service.reply(request).overlayText());

        verify(assistant).reply(eq("MOCK:1000:viewer-2"), anyString(), argThat(input ->
                !input.contains("<room_context>")
                        && input.contains("当前发言观众：viewer-2")
                        && input.contains("当前弹幕：刚刚那个人说得对吗？")
        ));
    }

    @Test
    void evictsTheLeastRecentlyUsedMemoryWhenCapacityIsExceeded() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        LiveHostConversationService service = service(assistant, 2);
        ReplyRequest firstViewer = request("viewer-1", "message-1", "一");
        ReplyRequest secondViewer = request("viewer-2", "message-2", "二");
        ReplyRequest thirdViewer = request("viewer-3", "message-3", "三");

        service.reply(firstViewer);
        service.reply(secondViewer);
        service.reply(thirdViewer);

        verify(assistant).evictChatMemory(firstViewer.memoryId());
    }

    @Test
    void evictsInactiveMemoriesAfterTheRetentionPeriod() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runningControl(),
                passthroughMetrics(),
                10,
                Duration.ofMinutes(30),
                clock
        );
        ReplyRequest firstViewer = request("viewer-1", "message-1", "一");
        ReplyRequest secondViewer = request("viewer-2", "message-2", "二");

        service.reply(firstViewer);
        clock.advance(Duration.ofMinutes(31));
        service.reply(secondViewer);

        verify(assistant).evictChatMemory(firstViewer.memoryId());
    }

    @Test
    void restoresThePreviousMemoryWhenOutputModerationBlocksTheReply() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        ModerationService moderationService = mock(ModerationService.class);
        ChatMemory memory = mock(ChatMemory.class);
        ReplyRequest request = request("viewer-1", "message-1", "正常问题");
        ChatMessage previousMessage = UserMessage.from("上一条消息");
        when(assistant.getChatMemory(request.memoryId())).thenReturn(memory);
        when(memory.messages()).thenReturn(java.util.List.of(previousMessage));
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString())).thenReturn(reply("包含炸弹内容"));
        when(moderationService.evaluateOutput("包含炸弹内容"))
                .thenReturn(ModerationOutcome.block("模型回复命中本地安全规则"));
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                moderationService,
                runningControl(),
                passthroughMetrics(),
                10,
                Duration.ofMinutes(30),
                Clock.systemUTC()
        );

        assertThrows(OutputModerationException.class, () -> service.reply(request));

        verify(memory).clear();
        verify(memory).add(previousMessage);
    }

    @Test
    void restoresThePreviousMemoryWhenModelGenerationFails() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        ChatMemory memory = mock(ChatMemory.class);
        ReplyRequest request = request("viewer-1", "message-1", "正常问题");
        ChatMessage previousMessage = UserMessage.from("上一条消息");
        when(assistant.getChatMemory(request.memoryId())).thenReturn(memory);
        when(memory.messages()).thenReturn(java.util.List.of(previousMessage));
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString()))
                .thenThrow(new IllegalStateException("model failed"));
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runningControl(),
                passthroughMetrics(),
                10,
                Duration.ofMinutes(30),
                Clock.systemUTC()
        );

        assertThrows(IllegalStateException.class, () -> service.reply(request));

        verify(memory).clear();
        verify(memory).add(previousMessage);
    }

    @Test
    void restoresThePreviousMemoryWhenPauseWinsAfterModelGeneration() {
        StreamHostAssistant assistant = mock(StreamHostAssistant.class);
        RuntimeControlService runtimeControlService = mock(RuntimeControlService.class);
        ChatMemory memory = mock(ChatMemory.class);
        ReplyRequest request = request("viewer-1", "message-1", "正常问题");
        ChatMessage previousMessage = UserMessage.from("上一条消息");
        when(assistant.getChatMemory(request.memoryId())).thenReturn(memory);
        when(memory.messages()).thenReturn(java.util.List.of(previousMessage));
        when(assistant.reply(eq(request.memoryId()), anyString(), anyString())).thenReturn(reply("正常回复"));
        when(runtimeControlService.isPaused()).thenReturn(true);
        LiveReplyMetrics metrics = passthroughMetrics();
        LiveHostConversationService service = new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runtimeControlService,
                metrics,
                10,
                Duration.ofMinutes(30),
                Clock.systemUTC()
        );

        assertThrows(ReplyPausedException.class, () -> service.reply(request));

        verify(memory).clear();
        verify(memory).add(previousMessage);
    }

    @Test
    void parsesTheCompletedStreamingReplyAndPassesTheHostProfile() {
        StreamHostAssistant synchronousAssistant = mock(StreamHostAssistant.class);
        StreamingStreamHostAssistant streamingAssistant = mock(StreamingStreamHostAssistant.class);
        ReplyRequest request = request("viewer-1", "message-1", "正常问题");
        LiveHostProfileSnapshot profile = LiveHostProfileSnapshot.defaults("1000");
        TokenStream tokenStream = completingTokenStream(
                "{\"overlayText\":\"流式回复\",\"danmakuText\":null,\"sendDanmaku\":false}"
        );
        when(streamingAssistant.reply(eq(request.memoryId()), eq(profile.systemPromptSection()), anyString()))
                .thenReturn(tokenStream);
        LiveHostConversationService service = streamingService(
                synchronousAssistant,
                streamingAssistant,
                allowingModeration()
        );

        LiveHostReply reply = service.replyStreaming(request, profile);

        assertEquals("流式回复", reply.overlayText());
        verify(streamingAssistant).reply(
                eq(request.memoryId()),
                eq(profile.systemPromptSection()),
                argThat(input -> input.contains("当前弹幕：正常问题"))
        );
    }

    @Test
    void restoresTheStreamingMemoryWhenTheFinalJsonCannotBeParsed() {
        StreamHostAssistant synchronousAssistant = mock(StreamHostAssistant.class);
        StreamingStreamHostAssistant streamingAssistant = mock(StreamingStreamHostAssistant.class);
        ChatMemory memory = mock(ChatMemory.class);
        ReplyRequest request = request("viewer-1", "message-1", "正常问题");
        ChatMessage previousMessage = UserMessage.from("上一条消息");
        LiveHostProfileSnapshot profile = LiveHostProfileSnapshot.defaults("1000");
        TokenStream tokenStream = completingTokenStream("not-json");
        when(streamingAssistant.getChatMemory(request.memoryId())).thenReturn(memory);
        when(memory.messages()).thenReturn(java.util.List.of(previousMessage));
        when(streamingAssistant.reply(eq(request.memoryId()), eq(profile.systemPromptSection()), anyString()))
                .thenReturn(tokenStream);
        LiveHostConversationService service = streamingService(
                synchronousAssistant,
                streamingAssistant,
                allowingModeration()
        );

        assertThrows(IllegalStateException.class, () -> service.replyStreaming(request, profile));

        verify(memory).clear();
        verify(memory).add(previousMessage);
    }

    private static LiveHostConversationService service(StreamHostAssistant assistant, int maxActiveMemories) {
        return new LiveHostConversationService(
                assistant,
                allowingModeration(),
                runningControl(),
                passthroughMetrics(),
                maxActiveMemories,
                Duration.ofMinutes(30),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static LiveHostConversationService streamingService(
            StreamHostAssistant synchronousAssistant,
            StreamingStreamHostAssistant streamingAssistant,
            ModerationService moderationService
    ) {
        return new LiveHostConversationService(
                synchronousAssistant,
                streamingAssistant,
                new ObjectMapper(),
                moderationService,
                runningControl(),
                passthroughMetrics(),
                mock(LiveHostProfileService.class),
                new LiveHostMemoryProperties(8, 10, Duration.ofMinutes(30), "test:chat-memory")
        );
    }

    private static TokenStream completingTokenStream(String text) {
        TokenStream tokenStream = mock(TokenStream.class);
        AtomicReference<Consumer<ChatResponse>> completionHandler = new AtomicReference<>();
        when(tokenStream.onCompleteResponse(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            completionHandler.set(invocation.getArgument(0));
            return tokenStream;
        });
        when(tokenStream.onError(org.mockito.ArgumentMatchers.any())).thenReturn(tokenStream);
        doAnswer(invocation -> {
            completionHandler.get().accept(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            return null;
        }).when(tokenStream).start();
        return tokenStream;
    }

    private static ModerationService allowingModeration() {
        ModerationService moderationService = mock(ModerationService.class);
        when(moderationService.evaluateOutput(anyString())).thenReturn(ModerationOutcome.allow());
        return moderationService;
    }

    private static RuntimeControlService runningControl() {
        return mock(RuntimeControlService.class);
    }

    private static LiveReplyMetrics passthroughMetrics() {
        LiveReplyMetrics metrics = mock(LiveReplyMetrics.class);
        when(metrics.recordModelCall(org.mockito.ArgumentMatchers.<java.util.function.Supplier<LiveHostReply>>any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<LiveHostReply>>getArgument(0).get());
        return metrics;
    }

    private static LiveHostReply reply(String overlayText) {
        return new LiveHostReply(overlayText, null, false);
    }

    private static ReplyRequest request(String senderId, String messageId, String messageText) {
        return new ReplyRequest(
                "MOCK",
                "1000",
                senderId,
                messageId,
                messageText,
                Instant.parse("2026-08-10T00:00:00Z")
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
