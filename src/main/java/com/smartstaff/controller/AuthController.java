package com.smartstaff.controller;

import com.smartstaff.dto.request.ApproveRequest;
import com.smartstaff.dto.request.LoginRequest;
import com.smartstaff.dto.request.SignupRequest;
import com.smartstaff.dto.response.AccountsResponse;
import com.smartstaff.dto.response.AuthResponse;
import com.smartstaff.dto.response.MeResponse;
import com.smartstaff.dto.response.SimpleResponse;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            @Valid @RequestBody SignupRequest req,
            @AuthenticationPrincipal User requester
    ) {
        boolean requesterIsAdmin = requester != null && requester.getRole() == Role.ADMIN;
        return ResponseEntity.ok(authService.signup(req, requesterIsAdmin));
    }

    @PostMapping("/logout")
    public ResponseEntity<SimpleResponse> logout() {
        // Stateless JWTs — nothing to revoke server-side yet (no denylist in v1).
        return ResponseEntity.ok(SimpleResponse.OK);
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new MeResponse(true, authService.me(user)));
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountsResponse> accounts() {
        return ResponseEntity.ok(authService.accounts());
    }

    @PostMapping("/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleResponse> approve(@Valid @RequestBody ApproveRequest req) {
        authService.setApproval(req.employee_id(), req.approved());
        return ResponseEntity.ok(SimpleResponse.OK);
    }
}
