package com.bilibili.ailive.liveplatform.bilibili;

import com.bilibili.ailive.liveplatform.LiveChatEvent;
import com.bilibili.ailive.liveplatform.LiveChatEventIngress;
import com.bilibili.ailive.liveplatform.LiveAudienceActivity;
import com.bilibili.ailive.liveplatform.LiveAudienceTracker;
import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import com.bilibili.ailive.liveplatform.ViewerEnteredEventIngress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "app.live-platform.bilibili.open-live-enabled",
        havingValue = "true"
)
class OfficialBilibiliLiveEventConnector implements BilibiliLiveEventConnector {

    private static final Logger logger = LoggerFactory.getLogger(OfficialBilibiliLiveEventConnector.class);

    private final BilibiliOpenLiveProperties properties;
    private final BilibiliOpenLiveApiClient apiClient;
    private final BilibiliMessageParser messageParser;
    private final LiveChatEventIngress eventIngress;
    private final ObjectMapper objectMapper;
    private final LiveAudienceTracker audienceTracker;
    private final ViewerEnteredEventIngress viewerEnteredEventIngress;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor eventExecutor;

    private volatile BilibiliConnectionStatus currentStatus;
    private volatile BilibiliOpenLiveSession session;
    private volatile WebSocket webSocket;
    private ScheduledFuture<?> websocketHeartbeat;
    private ScheduledFuture<?> authenticationTimeout;
    private ScheduledFuture<?> apiHeartbeat;
    private ScheduledFuture<?> reconnectTask;
    private long socketGeneration;
    private int consecutiveHeartbeatFailures;
    private int sessionRestartAttempts;
    private Instant lastSocketActivityAt;
    private volatile boolean stopping;

    OfficialBilibiliLiveEventConnector(
            BilibiliOpenLiveProperties properties,
            BilibiliOpenLiveApiClient apiClient,
            BilibiliMessageParser messageParser,
            LiveChatEventIngress eventIngress,
            ObjectMapper objectMapper,
            LiveAudienceTracker audienceTracker,
            ViewerEnteredEventIngress viewerEnteredEventIngress
    ) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.messageParser = messageParser;
        this.eventIngress = eventIngress;
        this.objectMapper = objectMapper;
        this.audienceTracker = audienceTracker;
        this.viewerEnteredEventIngress = viewerEnteredEventIngress;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
        this.scheduler = java.util.concurrent.Executors.newScheduledThreadPool(2);
        this.eventExecutor = new ThreadPoolExecutor(
                properties.eventWorkerThreads(),
                properties.eventWorkerThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.maxPendingEvents()),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.currentStatus = properties.isConfigured()
                ? BilibiliConnectionStatus.disconnected()
                : BilibiliConnectionStatus.notConfigured();
    }

    @PostConstruct
    void autoConnectIfConfigured() {
        if (!properties.autoConnect() || !properties.isConfigured()) {
            return;
        }
        scheduler.execute(() -> {
            try {
                connect();
            } catch (RuntimeException exception) {
                logger.warn("Automatic Bilibili Open Live connection failed", exception);
            }
        });
    }

    @Override
    public void connect() {
        try {
            connect(null);
        } catch (RuntimeException exception) {
            scheduleSessionRestart(safeMessage(exception));
            throw exception;
        }
    }

    private void connect(Long expectedGeneration) {
        properties.requireConfigured();
        synchronized (this) {
            if (expectedGeneration != null && expectedGeneration != socketGeneration) {
                return;
            }
            boolean expectedRestart = expectedGeneration != null
                    && currentStatus.state() == BilibiliConnectionState.RECONNECTING;
            if (session != null || (isTransitioning(currentStatus.state()) && !expectedRestart)) {
                return;
            }
            stopping = false;
            currentStatus = new BilibiliConnectionStatus(
                    BilibiliConnectionState.STARTING,
                    null,
                    null,
                    null,
                    null
            );
        }
        BilibiliOpenLiveSession startedSession = null;
        try {
            startedSession = apiClient.start();
            boolean cancelled;
            synchronized (this) {
                cancelled = stopping;
                if (!cancelled) {
                    session = startedSession;
                    consecutiveHeartbeatFailures = 0;
                    scheduleApiHeartbeat();
                }
            }
            if (cancelled) {
                safeEnd(startedSession);
                synchronized (this) {
                    currentStatus = BilibiliConnectionStatus.disconnected();
                    stopping = false;
                }
                return;
            }
            connectWebSocket();
        } catch (RuntimeException exception) {
            BilibiliOpenLiveSession failedSession;
            boolean cancelled;
            synchronized (this) {
                failedSession = session == null ? startedSession : session;
                cancelled = stopping;
                session = null;
                cancelScheduledTasks();
                currentStatus = cancelled
                        ? BilibiliConnectionStatus.disconnected()
                        : failedStatus(exception);
                stopping = false;
            }
            safeEnd(failedSession);
            if (cancelled) {
                return;
            }
            throw exception;
        }
    }

    @Override
    public void disconnect() {
        BilibiliOpenLiveSession closingSession;
        WebSocket closingSocket;
        synchronized (this) {
            if (session == null && currentStatus.state() == BilibiliConnectionState.STARTING) {
                stopping = true;
                currentStatus = statusWithState(BilibiliConnectionState.STOPPING, null);
                return;
            }
            if (session == null && currentStatus.state() != BilibiliConnectionState.RECONNECTING) {
                currentStatus = properties.isConfigured()
                        ? BilibiliConnectionStatus.disconnected()
                        : BilibiliConnectionStatus.notConfigured();
                return;
            }
            stopping = true;
            currentStatus = statusWithState(BilibiliConnectionState.STOPPING, null);
            closingSession = session;
            session = null;
            socketGeneration++;
            consecutiveHeartbeatFailures = 0;
            sessionRestartAttempts = 0;
            cancelScheduledTasks();
            closingSocket = webSocket;
            webSocket = null;
        }
        if (closingSocket != null) {
            closingSocket.sendClose(WebSocket.NORMAL_CLOSURE, "operator disconnect");
        }
        try {
            safeEnd(closingSession);
        } finally {
            synchronized (this) {
                currentStatus = BilibiliConnectionStatus.disconnected();
                stopping = false;
            }
        }
    }

    @Override
    public BilibiliConnectionStatus status() {
        return currentStatus;
    }

    private void connectWebSocket() {
        BilibiliOpenLiveSession activeSession = requireSession();
        RuntimeException lastFailure = null;
        for (String websocketUrl : activeSession.websocketUrls()) {
            long generation = nextSocketGeneration();
            try {
                WebSocket connectedSocket = httpClient.newWebSocketBuilder()
                        .connectTimeout(properties.requestTimeout())
                        .buildAsync(URI.create(websocketUrl), new SessionWebSocketListener(generation))
                        .join();
                synchronized (this) {
                    if (isCurrent(generation)) {
                        webSocket = connectedSocket;
                        cancelReconnectTask();
                    } else {
                        connectedSocket.abort();
                    }
                }
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                logger.warn("Unable to connect to Bilibili WebSocket endpoint");
            }
        }
        throw new BilibiliOpenLiveException("Unable to connect to any Bilibili WebSocket endpoint", lastFailure);
    }

    private synchronized void handleOpen(WebSocket socket, long generation) {
        if (!isCurrent(generation)) {
            socket.abort();
            return;
        }
        webSocket = socket;
        lastSocketActivityAt = Instant.now();
        currentStatus = statusWithState(BilibiliConnectionState.AUTHENTICATING, null);
        byte[] authBody = requireSession().authBody().getBytes(StandardCharsets.UTF_8);
        socket.sendBinary(
                ByteBuffer.wrap(BilibiliPacketCodec.encode(BilibiliPacketCodec.OP_AUTH, authBody)),
                true
        );
        scheduleAuthenticationTimeout(generation);
        scheduleWebsocketHeartbeat(generation);
    }

    private void handlePacket(BilibiliPacket packet, long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        synchronized (this) {
            if (!isCurrent(generation)) {
                return;
            }
            lastSocketActivityAt = Instant.now();
        }
        if (packet.operation() == BilibiliPacketCodec.OP_AUTH_REPLY) {
            handleAuthReply(packet.body(), generation);
        } else if (packet.operation() == BilibiliPacketCodec.OP_MESSAGE) {
            handleMessage(packet.body());
        }
    }

    private synchronized void handleAuthReply(byte[] body, long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        try {
            JsonNode response = objectMapper.readTree(body);
            if (response.path("code").asInt(-1) != 0) {
                handleConnectionFailure(new BilibiliOpenLiveException("Bilibili WebSocket authentication failed"), generation);
                return;
            }
            BilibiliOpenLiveSession activeSession = requireSession();
            cancelAuthenticationTimeout();
            currentStatus = new BilibiliConnectionStatus(
                    BilibiliConnectionState.CONNECTED,
                    activeSession.roomId(),
                    activeSession.gameId(),
                    Instant.now(),
                    null
            );
            sessionRestartAttempts = 0;
        } catch (Exception exception) {
            handleConnectionFailure(exception, generation);
        }
    }

    private void handleMessage(byte[] body) {
        BilibiliOpenLiveSession activeSession = session;
        if (activeSession == null) {
            return;
        }
        try {
            messageParser.parse(body, activeSession).ifPresent(this::submitEvent);
            messageParser.parseAudienceActivity(body)
                    .filter(activity -> !activity.viewerId().equals(activeSession.anchorOpenId()))
                    .ifPresent(activity -> submitAudienceActivity(activity, activeSession.gameId()));
            messageParser.parseViewerEntered(body)
                    .filter(event -> !event.viewerId().equals(activeSession.anchorOpenId()))
                    .ifPresent(event -> submitViewerEntered(event, activeSession.gameId()));
            JsonNode message = objectMapper.readTree(body);
            if ("LIVE_OPEN_PLATFORM_INTERACTION_END".equals(message.path("cmd").asText())) {
                handleSessionFailure("哔哩哔哩场次已结束，消息推送停止");
            }
        } catch (RuntimeException | java.io.IOException exception) {
            logger.warn("Ignoring malformed Bilibili live message", exception);
        }
    }

    private void submitAudienceActivity(LiveAudienceActivity activity, String sessionId) {
        try {
            eventExecutor.execute(() -> audienceTracker.observe(activity, sessionId));
        } catch (RejectedExecutionException exception) {
            logger.warn("Bilibili live event executor is full; dropping audience activity");
        }
    }

    private void submitViewerEntered(ViewerEnteredEvent event, String sessionId) {
        try {
            eventExecutor.execute(() -> viewerEnteredEventIngress.accept(event, sessionId));
        } catch (RejectedExecutionException exception) {
            logger.warn("Bilibili live event executor is full; dropping viewer entry");
        }
    }

    private void submitEvent(LiveChatEvent event) {
        try {
            eventExecutor.execute(() -> {
                try {
                    eventIngress.accept(event);
                } catch (RuntimeException exception) {
                    logger.warn("Bilibili live event processing failed for message {}", event.messageId(), exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.warn("Bilibili live event executor is full; dropping message {}", event.messageId());
        }
    }

    private synchronized void handleConnectionFailure(Throwable failure, long generation) {
        if (!isCurrent(generation) || stopping || session == null) {
            return;
        }
        socketGeneration++;
        cancelAuthenticationTimeout();
        cancelWebsocketHeartbeat();
        WebSocket failedSocket = webSocket;
        webSocket = null;
        if (failedSocket != null) {
            failedSocket.abort();
        }
        currentStatus = statusWithState(BilibiliConnectionState.RECONNECTING, safeMessage(failure));
        if (reconnectTask == null || reconnectTask.isDone()) {
            reconnectTask = scheduler.schedule(
                    this::attemptReconnect,
                    properties.reconnectDelay().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void attemptReconnect() {
        synchronized (this) {
            reconnectTask = null;
            if (stopping || session == null) {
                return;
            }
        }
        try {
            connectWebSocket();
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (!stopping && session != null) {
                    currentStatus = statusWithState(BilibiliConnectionState.RECONNECTING, safeMessage(exception));
                    reconnectTask = scheduler.schedule(
                            this::attemptReconnect,
                            properties.reconnectDelay().toMillis(),
                            TimeUnit.MILLISECONDS
                    );
                }
            }
        }
    }

    private synchronized void scheduleWebsocketHeartbeat(long generation) {
        cancelWebsocketHeartbeat();
        websocketHeartbeat = scheduler.scheduleAtFixedRate(
                () -> sendWebsocketHeartbeat(generation),
                properties.heartbeatInterval().toMillis(),
                properties.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private synchronized void scheduleAuthenticationTimeout(long generation) {
        cancelAuthenticationTimeout();
        authenticationTimeout = scheduler.schedule(
                () -> handleAuthenticationTimeout(generation),
                properties.requestTimeout().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void handleAuthenticationTimeout(long generation) {
        synchronized (this) {
            authenticationTimeout = null;
            if (!isCurrent(generation) || currentStatus.state() != BilibiliConnectionState.AUTHENTICATING) {
                return;
            }
        }
        handleConnectionFailure(
                new BilibiliOpenLiveException("Bilibili WebSocket authentication timed out"),
                generation
        );
    }

    private void sendWebsocketHeartbeat(long generation) {
        WebSocket activeSocket;
        synchronized (this) {
            if (!isCurrent(generation)) {
                return;
            }
            if (BilibiliReconnectPolicy.socketActivityTimedOut(
                    lastSocketActivityAt, Instant.now(), properties.heartbeatInterval())) {
                activeSocket = null;
            } else {
                activeSocket = webSocket;
            }
        }
        if (activeSocket == null) {
            handleConnectionFailure(
                    new BilibiliOpenLiveException("Bilibili WebSocket heartbeat response timed out"),
                    generation
            );
            return;
        }
        activeSocket.sendBinary(
                ByteBuffer.wrap(BilibiliPacketCodec.encode(BilibiliPacketCodec.OP_HEARTBEAT, new byte[0])),
                true
        ).exceptionally(failure -> {
            handleConnectionFailure(failure, generation);
            return null;
        });
    }

    private synchronized void scheduleApiHeartbeat() {
        if (apiHeartbeat != null) {
            apiHeartbeat.cancel(false);
        }
        apiHeartbeat = scheduler.scheduleWithFixedDelay(
                this::sendApiHeartbeat,
                properties.heartbeatInterval().toMillis(),
                properties.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void sendApiHeartbeat() {
        BilibiliOpenLiveSession activeSession = session;
        if (activeSession == null || stopping) {
            return;
        }
        try {
            apiClient.heartbeat(activeSession.gameId());
            synchronized (this) {
                if (session != activeSession || stopping) {
                    return;
                }
                consecutiveHeartbeatFailures = 0;
            }
        } catch (RuntimeException exception) {
            logger.warn("Bilibili project heartbeat failed", exception);
            boolean exhausted;
            synchronized (this) {
                if (session != activeSession || stopping) {
                    return;
                }
                consecutiveHeartbeatFailures++;
                exhausted = consecutiveHeartbeatFailures >= properties.maxHeartbeatFailures();
            }
            if (exhausted) {
                handleSessionFailure("哔哩哔哩项目心跳连续失败：" + safeMessage(exception));
            }
        }
    }

    private void handleSessionFailure(String reason) {
        BilibiliOpenLiveSession failedSession;
        long restartGeneration;
        boolean restartAutomatically = properties.autoConnect();
        synchronized (this) {
            if (session == null) {
                return;
            }
            stopping = true;
            failedSession = session;
            session = null;
            restartGeneration = ++socketGeneration;
            consecutiveHeartbeatFailures = 0;
            cancelScheduledTasks();
            if (webSocket != null) {
                webSocket.abort();
                webSocket = null;
            }
            currentStatus = new BilibiliConnectionStatus(
                    restartAutomatically ? BilibiliConnectionState.RECONNECTING : BilibiliConnectionState.FAILED,
                    currentStatus.roomId(),
                    currentStatus.gameId(),
                    currentStatus.connectedAt(),
                    reason
            );
        }
        safeEnd(failedSession);
        synchronized (this) {
            stopping = false;
            if (restartAutomatically
                    && restartGeneration == socketGeneration
                    && currentStatus.state() == BilibiliConnectionState.RECONNECTING) {
                scheduleSessionRestart(reason);
            }
        }
    }

    private synchronized void scheduleSessionRestart(String reason) {
        if (!properties.autoConnect() || stopping || session != null) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        if (sessionRestartAttempts < properties.maxSessionRestartAttempts()) {
            sessionRestartAttempts++;
        }
        int attempt = sessionRestartAttempts;
        long generation = socketGeneration;
        long delayMillis = BilibiliReconnectPolicy.sessionRestartDelayMillis(
                properties.reconnectDelay(), attempt);
        currentStatus = new BilibiliConnectionStatus(
                BilibiliConnectionState.RECONNECTING,
                currentStatus.roomId(),
                currentStatus.gameId(),
                currentStatus.connectedAt(),
                reason
        );
        logger.info(
                "Scheduling Bilibili Open Live session restart with backoff level {}/{} in {} ms",
                attempt,
                properties.maxSessionRestartAttempts(),
                delayMillis
        );
        reconnectTask = scheduler.schedule(
                () -> attemptSessionRestart(generation),
                delayMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void attemptSessionRestart(long generation) {
        synchronized (this) {
            reconnectTask = null;
            if (stopping || session != null || generation != socketGeneration) {
                return;
            }
        }
        try {
            connect(generation);
        } catch (RuntimeException exception) {
            logger.warn("Bilibili Open Live session restart failed", exception);
            scheduleSessionRestart(safeMessage(exception));
        }
    }

    private synchronized boolean isCurrent(long generation) {
        return generation == socketGeneration && session != null && !stopping;
    }

    private synchronized long nextSocketGeneration() {
        return ++socketGeneration;
    }

    private BilibiliOpenLiveSession requireSession() {
        BilibiliOpenLiveSession activeSession = session;
        if (activeSession == null) {
            throw new BilibiliOpenLiveException("No active Bilibili Open Live session");
        }
        return activeSession;
    }

    private synchronized void cancelScheduledTasks() {
        cancelAuthenticationTimeout();
        cancelWebsocketHeartbeat();
        if (apiHeartbeat != null) {
            apiHeartbeat.cancel(false);
            apiHeartbeat = null;
        }
        cancelReconnectTask();
    }

    private void cancelAuthenticationTimeout() {
        if (authenticationTimeout != null) {
            authenticationTimeout.cancel(false);
            authenticationTimeout = null;
        }
    }

    private void cancelReconnectTask() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void cancelWebsocketHeartbeat() {
        if (websocketHeartbeat != null) {
            websocketHeartbeat.cancel(false);
            websocketHeartbeat = null;
        }
    }

    private void safeEnd(BilibiliOpenLiveSession closingSession) {
        if (closingSession == null) {
            return;
        }
        try {
            apiClient.end(closingSession.gameId());
        } catch (RuntimeException exception) {
            logger.warn("Unable to close Bilibili Open Live session cleanly", exception);
        }
    }

    private BilibiliConnectionStatus statusWithState(BilibiliConnectionState state, String error) {
        BilibiliOpenLiveSession activeSession = session;
        return new BilibiliConnectionStatus(
                state,
                activeSession == null ? currentStatus.roomId() : activeSession.roomId(),
                activeSession == null ? currentStatus.gameId() : activeSession.gameId(),
                currentStatus.connectedAt(),
                error
        );
    }

    private BilibiliConnectionStatus failedStatus(Throwable exception) {
        return new BilibiliConnectionStatus(
                BilibiliConnectionState.FAILED,
                currentStatus.roomId(),
                currentStatus.gameId(),
                currentStatus.connectedAt(),
                safeMessage(exception)
        );
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static boolean isTransitioning(BilibiliConnectionState state) {
        return state == BilibiliConnectionState.STARTING
                || state == BilibiliConnectionState.AUTHENTICATING
                || state == BilibiliConnectionState.RECONNECTING
                || state == BilibiliConnectionState.STOPPING;
    }

    @PreDestroy
    void shutdown() {
        try {
            disconnect();
        } catch (RuntimeException exception) {
            logger.warn("Bilibili connector shutdown encountered an error", exception);
        } finally {
            scheduler.shutdownNow();
            eventExecutor.shutdownNow();
        }
    }

    private final class SessionWebSocketListener implements WebSocket.Listener {

        private final long generation;
        private final ByteArrayOutputStream binaryMessage = new ByteArrayOutputStream();

        private SessionWebSocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            handleOpen(webSocket, generation);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryMessage.writeBytes(chunk);
            if (last) {
                byte[] message = binaryMessage.toByteArray();
                binaryMessage.reset();
                try {
                    BilibiliPacketCodec.decode(message).forEach(packet -> handlePacket(packet, generation));
                } catch (RuntimeException exception) {
                    logger.warn("Unable to decode Bilibili WebSocket packet", exception);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleConnectionFailure(
                    new BilibiliOpenLiveException("WebSocket closed: " + statusCode + " " + reason),
                    generation
            );
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleConnectionFailure(error, generation);
        }
    }
}
