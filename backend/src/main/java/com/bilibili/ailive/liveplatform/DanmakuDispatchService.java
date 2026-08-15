package com.bilibili.ailive.liveplatform;

import com.bilibili.ailive.moderation.ModerationOutcome;
import com.bilibili.ailive.moderation.ModerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DanmakuDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(DanmakuDispatchService.class);

    private final Map<LivePlatform, LivePlatformGateway> gateways;
    private final DanmakuProperties properties;
    private final ModerationService moderationService;
    private final OutboundDanmakuEchoGuard echoGuard;
    private final Clock clock;
    private final Map<String, Instant> lastSentByRoom = new HashMap<>();

    @Autowired
    DanmakuDispatchService(
            List<LivePlatformGateway> gateways,
            DanmakuProperties properties,
            ModerationService moderationService,
            OutboundDanmakuEchoGuard echoGuard
    ) {
        this(gateways, properties, moderationService, echoGuard, Clock.systemUTC());
    }

    DanmakuDispatchService(
            List<LivePlatformGateway> gateways,
            DanmakuProperties properties,
            ModerationService moderationService,
            OutboundDanmakuEchoGuard echoGuard,
            Clock clock
    ) {
        this.gateways = new EnumMap<>(LivePlatform.class);
        gateways.forEach(gateway -> this.gateways.put(gateway.platform(), gateway));
        this.properties = properties;
        this.moderationService = moderationService;
        this.echoGuard = echoGuard;
        this.clock = clock;
    }

    public synchronized DanmakuDispatchResult dispatch(String platformName, String roomId, String text) {
        LivePlatform platform;
        try {
            platform = LivePlatform.valueOf(platformName);
        } catch (IllegalArgumentException exception) {
            return DanmakuDispatchResult.failed("不支持的直播平台");
        }
        LivePlatformGateway gateway = gateways.get(platform);
        if (gateway == null || !gateway.canSendDanmaku()) {
            return DanmakuDispatchResult.skipped("当前平台尚未启用弹幕发送能力");
        }
        if (text == null || text.isBlank()) {
            return DanmakuDispatchResult.skipped("模型未提供弹幕短句");
        }
        String normalizedText = text.trim();
        if (normalizedText.codePointCount(0, normalizedText.length()) > properties.maxCharacters()) {
            return DanmakuDispatchResult.skipped("弹幕短句超过长度限制");
        }
        ModerationOutcome moderation = moderationService.evaluateOutput(normalizedText);
        if (!moderation.allowed()) {
            return DanmakuDispatchResult.skipped(moderation.reason());
        }
        Instant now = clock.instant();
        String roomKey = platform.name() + ":" + roomId;
        Instant nextAllowedAt = lastSentByRoom.getOrDefault(roomKey, Instant.MIN).plus(properties.minimumInterval());
        if (now.isBefore(nextAllowedAt)) {
            return DanmakuDispatchResult.skipped("AI 弹幕发送间隔尚未结束");
        }
        try {
            DanmakuSendResult result = gateway.sendDanmaku(new DanmakuSendRequest(platform, roomId, normalizedText));
            if (!result.sent()) {
                return DanmakuDispatchResult.failed(result.reason());
            }
            lastSentByRoom.put(roomKey, now);
            echoGuard.record(platform, roomId, normalizedText, result.platformMessageId());
            return DanmakuDispatchResult.sent(result.platformMessageId());
        } catch (RuntimeException exception) {
            logger.warn("Danmaku delivery failed for platform {} room {}", platform, roomId, exception);
            return DanmakuDispatchResult.failed("平台弹幕发送失败");
        }
    }
}
