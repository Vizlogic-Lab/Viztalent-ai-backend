package com.smartstaff.service.impl;

import com.smartstaff.dto.request.LoginRequest;
import com.smartstaff.dto.request.SignupRequest;
import com.smartstaff.dto.response.AccountResponse;
import com.smartstaff.dto.response.AccountsResponse;
import com.smartstaff.dto.response.AuthResponse;
import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.UserMapper;
import com.smartstaff.repository.UserRepository;
import com.smartstaff.security.JwtService;
import com.smartstaff.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            JwtService jwtService, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    private Role parseRole(String raw) {
        if ("admin".equalsIgnoreCase(raw)) return Role.ADMIN;
        if ("user".equalsIgnoreCase(raw) || "employee".equalsIgnoreCase(raw)) return Role.USER;
        throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown role: " + raw);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest req) {
        Role role = parseRole(req.role());
        Optional<User> found = role == Role.ADMIN
                ? userRepository.findByRoleAndEmailIgnoreCase(Role.ADMIN, req.identifier())
                : userRepository.findByRoleAndEmployeeIdIgnoreCase(Role.USER, req.identifier());

        User user = found.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect credentials."));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect credentials.");
        }
        if (role == Role.USER && user.getStatus() != AccountStatus.APPROVED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Your account is awaiting admin approval.", "pending_approval");
        }

        Instant now = Instant.now();
        user.setLastLoginAt(now);
        user.setLastSeenAt(now);
        userRepository.save(user);

        String token = jwtService.issueToken(user.getId(), role.name());
        return AuthResponse.success(token, userMapper.toAccountResponse(user));
    }

    @Override
    @Transactional
    public AuthResponse signup(SignupRequest req, boolean requesterIsAdmin) {
        // Validate password policy: 10+ chars, 1 letter, 1 digit
        validatePasswordPolicy(req.password());

        Role role = parseRole(req.role());

        if (role == Role.ADMIN) {
            // Only an existing admin may create another admin. A visitor hitting the
            // public /signup form with role=admin is refused (the seeder guarantees a
            // bootstrap admin already exists, so this never locks anyone out).
            if (!requesterIsAdmin) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Only an existing admin can create an admin account.", "admin_only");
            }
            if (req.email() == null || req.email().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Email is required for an admin account.");
            }
            if (userRepository.existsByRoleAndEmailIgnoreCase(Role.ADMIN, req.email())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "An admin with that email already exists.");
            }
            User admin = new User(Role.ADMIN, blankToNull(req.name(), req.email()), req.email(), null,
                    passwordEncoder.encode(req.password()), AccountStatus.APPROVED);
            userRepository.save(admin);
            String token = jwtService.issueToken(admin.getId(), Role.ADMIN.name());
            return AuthResponse.success(token, userMapper.toAccountResponse(admin));
        }

        // Employee signup.
        if (req.employee_id() == null || req.employee_id().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee ID is required.");
        }
        if (userRepository.existsByRoleAndEmployeeIdIgnoreCase(Role.USER, req.employee_id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That Employee ID is already registered.");
        }
        // Admin-created employees (via the Employees page) skip the approval queue;
        // self-service signups from the public /signup form wait for one.
        AccountStatus status = requesterIsAdmin ? AccountStatus.APPROVED : AccountStatus.PENDING;
        User employee = new User(Role.USER, blankToNull(req.name(), req.employee_id()), req.email(),
                req.employee_id(), passwordEncoder.encode(req.password()), status);
        userRepository.save(employee);

        if (status == AccountStatus.PENDING) {
            return AuthResponse.pendingApproval(userMapper.toAccountResponse(employee), "Account created. Waiting for admin approval.");
        }

        String token = jwtService.issueToken(employee.getId(), Role.USER.name());
        return AuthResponse.success(token, userMapper.toAccountResponse(employee));
    }

    @Override
    public AccountResponse me(User user) {
        return userMapper.toAccountResponse(user);
    }

    @Override
    public AccountsResponse accounts() {
        return new AccountsResponse(
                true,
                userRepository.findByRoleOrderByNameAsc(Role.ADMIN).stream().map(userMapper::toAccountResponse).toList(),
                userRepository.findByRoleOrderByNameAsc(Role.USER).stream().map(userMapper::toAccountResponse).toList()
        );
    }

    @Override
    @Transactional
    public void setApproval(String employeeId, boolean approved) {
        User user = userRepository.findByRoleAndEmployeeIdIgnoreCase(Role.USER, employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found."));
        user.setStatus(approved ? AccountStatus.APPROVED : AccountStatus.REJECTED);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }

        // Validate new password policy: 10+ chars, 1 letter, 1 digit
        if (newPassword.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 10 characters.");
        }
        if (!newPassword.matches(".*[a-zA-Z].*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must contain at least one letter.");
        }
        if (!newPassword.matches(".*\\d.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must contain at least one digit.");
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private static void validatePasswordPolicy(String password) {
        if (password.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 10 characters.");
        }
        if (!password.matches(".*[a-zA-Z].*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must contain at least one letter.");
        }
        if (!password.matches(".*\\d.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must contain at least one digit.");
        }
    }

    private static String blankToNull(String name, String fallback) {
        return (name == null || name.isBlank()) ? fallback : name;
    }
}
