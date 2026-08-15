package com.bilibili.ailive.conversation;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import com.bilibili.ailive.runtime.RuntimeControlService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

@Service
class LiveHostConversationService {

    private static final int LOCK_STRIPES = 64;
    private static final ZoneId LIVE_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LIVE_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");
    private final StreamHostAssistant streamHostAssistant;
    private final StreamingStreamHostAssistant streamingStreamHostAssistant;
    private final ObjectMapper objectMapper;
    private final ModerationService moderationService;
    private final RuntimeControlService runtimeControlService;
    private final LiveReplyMetrics metrics;
    private final LiveHostProfileService liveHostProfileService;
    private final int maxActiveMemories;
    private final Duration memoryRetention;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private final Map<String, Instant> lastAccessByMemoryId = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    LiveHostConversationService(
            StreamHostAssistant streamHostAssistant,
            StreamingStreamHostAssistant streamingStreamHostAssistant,
            ObjectMapper objectMapper,
            ModerationService moderationService,
            RuntimeControlService runtimeControlService,
            LiveReplyMetrics metrics,
            LiveHostProfileService liveHostProfileService,
            LiveHostMemoryProperties properties
    ) {
        this(
                streamHostAssistant,
                streamingStreamHostAssistant,
                objectMapper,
                moderationService,
                runtimeControlService,
                metrics,
                liveHostProfileService,
                properties.maxActiveMemories(),
                properties.ttl(),
                Clock.systemUTC()
        );
    }

    LiveHostConversationService(
            StreamHostAssistant streamHostAssistant,
            ModerationService moderationService,
            RuntimeControlService runtimeControlService,
            LiveReplyMetrics metrics,
            int maxActiveMemories,
            Duration memoryRetention,
            Clock clock
    ) {
        this(
                streamHostAssistant,
                null,
                new ObjectMapper(),
                moderationService,
                runtimeControlService,
                metrics,
                null,
                maxActiveMemories,
                memoryRetention,
                clock
        );
    }

    private LiveHostConversationService(
            StreamHostAssistant streamHostAssistant,
            StreamingStreamHostAssistant streamingStreamHostAssistant,
            ObjectMapper objectMapper,
            ModerationService moderationService,
            RuntimeControlService runtimeControlService,
            LiveReplyMetrics metrics,
            LiveHostProfileService liveHostProfileService,
            int maxActiveMemories,
            Duration memoryRetention,
            Clock clock
    ) {
        if (maxActiveMemories < 1 || memoryRetention.isNegative() || memoryRetention.isZero()) {
            throw new IllegalArgumentException("Live host memory limits must be positive");
        }
        this.streamHostAssistant = streamHostAssistant;
        this.streamingStreamHostAssistant = streamingStreamHostAssistant;
        this.objectMapper = objectMapper;
        this.moderationService = moderationService;
        this.runtimeControlService = runtimeControlService;
        this.metrics = metrics;
        this.liveHostProfileService = liveHostProfileService;
        this.maxActiveMemories = maxActiveMemories;
        this.memoryRetention = memoryRetention;
        this.clock = clock;
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    LiveHostReply reply(ReplyRequest request) {
        LiveHostProfileSnapshot profile = resolveProfile(request.roomId());
        return replyWithModel(
                request,
                streamHostAssistant,
                profile,
                () -> streamHostAssistant.reply(
                        request.memoryId(),
                        profile.systemPromptSection(),
                        viewerInput(request)
                )
        );
    }

    LiveHostReply replyStreaming(ReplyRequest request) {
        return replyStreaming(request, resolveProfile(request.roomId()));
    }

    LiveHostReply replyStreaming(ReplyRequest request, LiveHostProfileSnapshot profile) {
        if (streamingStreamHostAssistant == null) {
            return replyWithModel(
                    request,
                    streamHostAssistant,
                    profile,
                    () -> streamHostAssistant.reply(
                            request.memoryId(),
                            profile.systemPromptSection(),
                            viewerInput(request)
                    )
            );
        }
        return replyWithModel(
                request,
                streamingStreamHostAssistant,
                profile,
                () -> awaitStreamingReply(request, profile)
        );
    }

    private LiveHostReply replyWithModel(
            ReplyRequest request,
            ChatMemoryAccess memoryAccess,
            LiveHostProfileSnapshot profile,
            Supplier<LiveHostReply> modelCall
    ) {
        String memoryId = request.memoryId();
        ReentrantLock lock = lockFor(memoryId);
        lock.lock();
        try {
            activateMemory(memoryId);
            List<ChatMessage> memorySnapshot = snapshotMemory(memoryAccess, memoryId);
            try {
                WebSearchTools.beginUsageTracking(memoryId);
                LiveHostReply reply = metrics.recordModelCall(modelCall);
                boolean searchUsed = WebSearchTools.consumeUsage(memoryId);
                if (reply == null || reply.overlayText() == null) {
                    restoreMemory(memoryAccess, memoryId, memorySnapshot);
                    return reply;
                }
                if (reply.overlayText().codePointCount(0, reply.overlayText().length()) > profile.maxReplyCharacters()) {
                    throw new OutputModerationException("模型回复超过当前主播配置的长度限制");
                }
                if (profile.containsForbiddenTopic(reply.overlayText())) {
                    throw new OutputModerationException("模型回复涉及当前直播间禁止话题");
                }
                ModerationOutcome outputModeration = moderationService.evaluateOutput(reply.overlayText());
                if (!outputModeration.allowed()) {
                    throw new OutputModerationException(outputModeration.reason());
                }
                if (reply.sendDanmaku()) {
                    if (reply.danmakuText() == null) {
                        reply = reply.withoutDanmaku();
                    } else {
                        ModerationOutcome danmakuModeration = moderationService.evaluateOutput(reply.danmakuText());
                        if (!danmakuModeration.allowed()) {
                            reply = reply.withoutDanmaku();
                        }
                    }
                }
                if (runtimeControlService.isPaused()) {
                    throw new ReplyPausedException();
                }
                return searchUsed ? reply.withSearchAttribution() : reply;
            } catch (RuntimeException exception) {
                WebSearchTools.consumeUsage(memoryId);
                restoreMemory(memoryAccess, memoryId, memorySnapshot);
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    private LiveHostReply awaitStreamingReply(ReplyRequest request, LiveHostProfileSnapshot profile) {
        CompletableFuture<LiveHostReply> completion = new CompletableFuture<>();
        try {
            streamingStreamHostAssistant.reply(
                            request.memoryId(),
                            profile.systemPromptSection(),
                            viewerInput(request)
                    )
                    .onCompleteResponse(response -> {
                        try {
                            completion.complete(objectMapper.readValue(
                                    response.aiMessage().text(),
                                    LiveHostReply.class
                            ));
                        } catch (Exception exception) {
                            completion.completeExceptionally(exception);
                        }
                    })
                    .onError(completion::completeExceptionally)
                    .start();
            return completion.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to parse the streaming model reply", exception.getCause());
        }
    }

    private LiveHostProfileSnapshot resolveProfile(String roomId) {
        return liveHostProfileService == null
                ? LiveHostProfileSnapshot.defaults(roomId)
                : liveHostProfileService.resolve(roomId);
    }

    private String viewerInput(ReplyRequest request) {
        ZonedDateTime now = clock.instant().atZone(LIVE_TIME_ZONE);
        return """
                当前日期时间（中国标准时间，可信系统时间）：%s，星期%s
                当前发言观众：%s
                当前弹幕：%s
                """.formatted(
                now.format(LIVE_DATE_TIME_FORMAT),
                chineseWeekday(now),
                request.senderName(),
                request.messageText()
        );
    }

    private static String chineseWeekday(ZonedDateTime dateTime) {
        return switch (dateTime.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private List<ChatMessage> snapshotMemory(ChatMemoryAccess memoryAccess, String memoryId) {
        ChatMemory memory = memoryAccess.getChatMemory(memoryId);
        return memory == null ? List.of() : List.copyOf(memory.messages());
    }

    private void restoreMemory(ChatMemoryAccess memoryAccess, String memoryId, List<ChatMessage> snapshot) {
        ChatMemory memory = memoryAccess.getChatMemory(memoryId);
        if (memory == null) {
            return;
        }
        memory.clear();
        snapshot.forEach(memory::add);
    }

    private ReentrantLock lockFor(String memoryId) {
        int index = Math.floorMod(memoryId.hashCode(), locks.length);
        return locks[index];
    }

    private synchronized void activateMemory(String activeMemoryId) {
        Instant now = clock.instant();
        Instant expiresBefore = now.minus(memoryRetention);
        Iterator<Map.Entry<String, Instant>> iterator = lastAccessByMemoryId.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> entry = iterator.next();
            if (!entry.getValue().isBefore(expiresBefore)) {
                continue;
            }
            iterator.remove();
            evictChatMemory(entry.getKey());
        }

        lastAccessByMemoryId.put(activeMemoryId, now);
        while (lastAccessByMemoryId.size() > maxActiveMemories) {
            String eldestMemoryId = lastAccessByMemoryId.keySet().iterator().next();
            lastAccessByMemoryId.remove(eldestMemoryId);
            evictChatMemory(eldestMemoryId);
        }
    }

    private void evictChatMemory(String memoryId) {
        streamHostAssistant.evictChatMemory(memoryId);
        if (streamingStreamHostAssistant != null) {
            streamingStreamHostAssistant.evictChatMemory(memoryId);
        }
    }
}
