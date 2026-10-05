package com.smartstaff.ai;

import com.smartstaff.ai.AiConfigService.AiConfigRequest;
import com.smartstaff.ai.AiConfigService.AiConfigView;
import com.smartstaff.ai.AiConfigService.ConnectionTest;
import com.smartstaff.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Admin: AI model choice, connection test, model list and usage. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiController {

    private final AiConfigService config;
    private final AiConnectionService connection;
    private final AiUsageService usage;

    public AiController(AiConfigService config, AiConnectionService connection, AiUsageService usage) {
        this.config = config;
        this.connection = connection;
        this.usage = usage;
    }

    @GetMapping("/api/config/ai")
    public ResponseEntity<AiConfigView> get() {
        return ResponseEntity.ok(config.view());
    }

    @PutMapping("/api/config/ai")
    public ResponseEntity<AiConfigView> save(@RequestBody AiConfigRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(config.save(req, admin));
    }

    @GetMapping("/api/config/gemini/models")
    public ResponseEntity<AiConnectionService.ModelList> models() {
        return ResponseEntity.ok(connection.models());
    }

    @PostMapping("/api/config/gemini/test")
    public ResponseEntity<ConnectionTest> test() {
        return ResponseEntity.ok(connection.test());
    }

    @GetMapping("/api/analytics/ai_usage")
    public ResponseEntity<AiUsageService.UsageView> usage(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(usage.view(days));
    }
}
