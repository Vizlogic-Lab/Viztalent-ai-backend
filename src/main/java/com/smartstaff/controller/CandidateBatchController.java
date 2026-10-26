package com.smartstaff.controller;

import com.smartstaff.service.impl.ScreeningBatchService;
import com.smartstaff.service.impl.ScreeningBatchService.EnterResult;
import com.smartstaff.service.impl.ScreeningBatchService.EntryInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public candidate entry for a shared batch link. The link's token plus the email and name from the
 *  candidate's resume is the only credential — permitAll in SecurityConfig and rate-limited per client
 *  address like the other token endpoints. */
@RestController
@RequestMapping("/api/candidate/batch/{token}")
public class CandidateBatchController {

    public record EnterRequest(String email, String name) {}

    private final ScreeningBatchService service;

    public CandidateBatchController(ScreeningBatchService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<EntryInfo> info(@PathVariable String token) {
        return ResponseEntity.ok(service.info(token));
    }

    @PostMapping("/enter")
    public ResponseEntity<EnterResult> enter(@PathVariable String token, @RequestBody EnterRequest req) {
        return ResponseEntity.ok(service.enter(token, req == null ? null : req.email(), req == null ? null : req.name()));
    }
}
