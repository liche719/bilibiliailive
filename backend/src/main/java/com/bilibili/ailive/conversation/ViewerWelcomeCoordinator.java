package com.bilibili.ailive.conversation;

import com.bilibili.ailive.liveplatform.ViewerEnteredEvent;
import com.bilibili.ailive.liveplatform.ViewerEnteredEventIngress;
import com.bilibili.ailive.overlay.OverlayPublisher;
import com.bilibili.ailive.overlay.OverlayWelcome;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ViewerWelcomeCoordinator implements ViewerEnteredEventIngress {

    private final ViewerWelcomeAdmission admission;
    private final ViewerWelcomeProperties properties;
    private final LiveHostProfileService profileService;
    private final RoomReplyScheduler replyScheduler;
    private final OverlayPublisher overlayPublisher;
    private final Clock clock;
    private final Map<String, PendingWelcome> pendingByRoom = new LinkedHashMap<>();

    @Autowired
    ViewerWelcomeCoordinator(
            ViewerWelcomeAdmission admission,
            ViewerWelcomeProperties properties,
            LiveHostProfileService profileService,
            RoomReplyScheduler replyScheduler,
            OverlayPublisher overlayPublisher
    ) {
        this(admission, properties, profileService, replyScheduler, overlayPublisher, Clock.systemUTC());
    }

    ViewerWelcomeCoordinator(
            ViewerWelcomeAdmission admission,
            ViewerWelcomeProperties properties,
            LiveHostProfileService profileService,
            RoomReplyScheduler replyScheduler,
            OverlayPublisher overlayPublisher,
            Clock clock
    ) {
        this.admission = admission;
        this.properties = properties;
        this.profileService = profileService;
        this.replyScheduler = replyScheduler;
        this.overlayPublisher = overlayPublisher;
        this.clock = clock;
    }

    @Override
    public synchronized void accept(ViewerEnteredEvent event, String sessionId) {
        if (!properties.enabled() || !admission.admit(event, sessionId)) {
            return;
        }
        Instant now = clock.instant();
        pendingByRoom.compute(event.roomExecutionKey(), (roomKey, current) -> {
            PendingWelcome pending = current == null
                    ? new PendingWelcome(event.roomId(), now, now, new LinkedHashMap<>())
                    : current;
            if (pending.viewers().size() < properties.maxPendingViewers()) {
                pending.viewers().putIfAbsent(event.viewerId(), event.viewerName());
            }
            return new PendingWelcome(pending.roomId(), pending.firstSeenAt(), now, pending.viewers());
        });
    }

    @Scheduled(fixedDelayString = "${app.live-platform.welcome.flush-interval:PT1S}")
    synchronized void flushReady() {
        Instant now = clock.instant();
        List<String> completedRooms = new ArrayList<>();
        pendingByRoom.forEach((roomKey, pending) -> {
            if (now.isBefore(pending.lastSeenAt().plus(properties.aggregationWindow()))) {
                return;
            }
            boolean busy = replyScheduler.isBusy(roomKey) || overlayPublisher.isReplyActive();
            if (busy && now.isBefore(pending.firstSeenAt().plus(properties.maxDelay()))) {
                return;
            }
            completedRooms.add(roomKey);
            if (!busy) {
                overlayPublisher.welcome(toOverlayWelcome(pending, now));
            }
        });
        completedRooms.forEach(pendingByRoom::remove);
    }

    private OverlayWelcome toOverlayWelcome(PendingWelcome pending, Instant now) {
        List<String> names = pending.viewers().values().stream()
                .filter(name -> name != null && !name.isBlank())
                .limit(properties.maxNamedViewers())
                .toList();
        String configuredWelcome = profileService.resolve(pending.roomId()).welcomeMessage().trim();
        String base = configuredWelcome.isBlank() ? "欢迎来到直播间" : configuredWelcome;
        String text;
        if (names.isEmpty()) {
            text = base + "，新来的朋友们～";
        } else {
            String mentioned = names.stream().map(name -> "@" + name).reduce((left, right) -> left + "、" + right).orElse("");
            int unnamed = pending.viewers().size() - names.size();
            text = unnamed > 0
                    ? mentioned + "，还有刚进来的 " + unnamed + " 位朋友，" + base + "～"
                    : mentioned + "，" + base + "～";
        }
        return new OverlayWelcome(
                UUID.randomUUID(),
                pending.roomId(),
                names,
                pending.viewers().size(),
                text,
                properties.displayDuration().toMillis(),
                now
        );
    }

    private record PendingWelcome(
            String roomId,
            Instant firstSeenAt,
            Instant lastSeenAt,
            LinkedHashMap<String, String> viewers
    ) {
    }
}
