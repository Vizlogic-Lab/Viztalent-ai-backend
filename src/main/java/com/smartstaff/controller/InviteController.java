package com.smartstaff.controller;

import com.smartstaff.dto.request.InviteMintRequest;
import com.smartstaff.dto.response.InviteMintResponse;
import com.smartstaff.entity.User;
import com.smartstaff.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/api/invites/mint")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<InviteMintResponse> mint(@Valid @RequestBody InviteMintRequest req,
                                                     @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(inviteService.mint(req, admin));
    }
}
