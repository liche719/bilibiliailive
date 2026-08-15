package com.bilibili.ailive.tts;

import com.bilibili.ailive.overlay.OverlayHub;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/tts")
class TtsController {

    private final LocalTtsService ttsService;
    private final OverlayHub overlayHub;

    TtsController(LocalTtsService ttsService, OverlayHub overlayHub) {
        this.ttsService = ttsService;
        this.overlayHub = overlayHub;
    }

    @GetMapping("/settings")
    TtsSettings settings() {
        return ttsService.settings();
    }

    @PutMapping("/settings")
    TtsSettings update(@Valid @RequestBody TtsSettingsRequest request) {
        TtsSettings settings = ttsService.update(request);
        overlayHub.ttsSettingsChanged(settings);
        return settings;
    }

    @GetMapping(value = "/audio/{candidateId}", produces = "audio/wav")
    ResponseEntity<Resource> audio(@PathVariable UUID candidateId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(ttsService.audio(candidateId));
    }
}
