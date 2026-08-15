package com.bilibili.ailive.conversation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/host-profile")
class LiveHostProfileController {

    private final LiveHostProfileService service;

    LiveHostProfileController(LiveHostProfileService service) {
        this.service = service;
    }

    @GetMapping
    LiveHostProfileResponse get(@PathVariable @NotBlank @Size(max = 64) String roomId) {
        return LiveHostProfileResponse.from(service.resolve(roomId));
    }

    @PutMapping
    LiveHostProfileResponse save(
            @PathVariable @NotBlank @Size(max = 64) String roomId,
            @Valid @RequestBody LiveHostProfileRequest request
    ) {
        return LiveHostProfileResponse.from(service.save(roomId, request));
    }
}
