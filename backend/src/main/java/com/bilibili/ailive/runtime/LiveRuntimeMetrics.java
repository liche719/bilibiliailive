package com.bilibili.ailive.runtime;

import com.bilibili.ailive.overlay.OverlayHub;
import com.bilibili.ailive.shared.SseHub;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class LiveRuntimeMetrics {

    LiveRuntimeMetrics(MeterRegistry meterRegistry, OverlayHub overlayHub, SseHub sseHub) {
        Gauge.builder("ai.live.overlay.subscribers", overlayHub, OverlayHub::subscriberCount)
                .register(meterRegistry);
        Gauge.builder("ai.live.control.subscribers", sseHub, SseHub::subscriberCount)
                .register(meterRegistry);
    }
}
