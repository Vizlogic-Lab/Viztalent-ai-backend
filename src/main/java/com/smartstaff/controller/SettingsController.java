package com.smartstaff.controller;

import com.smartstaff.dto.request.*;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import com.smartstaff.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/api/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConfigResponse> getConfig() {
        return ResponseEntity.ok(settingsService.getConfig());
    }

    @PostMapping("/api/config/gemini")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SaveStatusResponse> saveGemini(@RequestBody GeminiKeyRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settingsService.saveGeminiKey(req, admin));
    }

    @PostMapping("/api/config/gemini/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GeminiTestResponse> testGemini() {
        return ResponseEntity.ok(settingsService.testGeminiKey());
    }

    @PostMapping("/api/config/public_url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PublicUrlSaveResponse> savePublicUrl(@Valid @RequestBody PublicUrlRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settingsService.savePublicUrl(req, admin));
    }

    @PostMapping("/api/config/invite_ttl")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InviteTtlSaveResponse> saveInviteTtl(@Valid @RequestBody InviteTtlRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settingsService.saveInviteTtl(req, admin));
    }

    @PostMapping("/api/config/twilio")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TwilioSaveResponse> saveTwilio(@RequestBody TwilioConfigRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settingsService.saveTwilio(req, admin));
    }

    @PostMapping("/api/config/twilio/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TwilioTestResponse> testTwilio() {
        return ResponseEntity.ok(settingsService.testTwilio());
    }

    @PostMapping("/api/config/piston_url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CodeRunnerConfigInfo> savePistonUrl(@RequestBody PistonUrlRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settingsService.savePistonUrl(req, admin));
    }

    @PostMapping("/api/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleResponse> reset() {
        return ResponseEntity.ok(settingsService.reset());
    }
}
