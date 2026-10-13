package com.smartstaff.proctoring;

import com.smartstaff.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Admin: Settings → Proctoring. */
@RestController
@RequestMapping("/api/config/proctoring")
@PreAuthorize("hasRole('ADMIN')")
public class ProctoringController {

    private final ProctoringSettingsService settings;

    public ProctoringController(ProctoringSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<ProctoringSettings> get() {
        return ResponseEntity.ok(settings.current());
    }

    @PutMapping
    public ResponseEntity<ProctoringSettings> save(@RequestBody ProctoringSettings req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settings.save(req, admin));
    }
}
