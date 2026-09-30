package com.smartstaff.controller;

import com.smartstaff.dto.response.PistonTestResponse;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CodeRunnerController {

    private final CodeRunnerService codeRunnerService;
    private final SettingsService settingsService;

    public CodeRunnerController(CodeRunnerService codeRunnerService, SettingsService settingsService) {
        this.codeRunnerService = codeRunnerService;
        this.settingsService = settingsService;
    }

    /** Runs a hello-world in each supported language against the configured Piston. */
    @PostMapping("/api/config/piston/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PistonTestResponse> test() {
        List<PistonTestResponse.Language> languages = codeRunnerService.selfTest().stream()
                .map(c -> new PistonTestResponse.Language(c.language(), c.ok(), c.version(), c.message()))
                .toList();
        boolean ok = !languages.isEmpty() && languages.stream().allMatch(PistonTestResponse.Language::ok);
        return ResponseEntity.ok(new PistonTestResponse(ok, settingsService.getPistonUrlOrNull(), languages));
    }
}
