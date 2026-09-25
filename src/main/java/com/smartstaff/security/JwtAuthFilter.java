package com.smartstaff.security;

import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads "Authorization: Bearer <token>", resolves it to a User, and — while
 *  we're already touching the row — bumps last_seen_at so the Employees
 *  page's online/offline dots stay accurate without a dedicated heartbeat
 *  endpoint. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Optional<UUID> userId = jwtService.validateAndGetUserId(token);
            if (userId.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                userRepository.findById(userId.get()).filter(JwtAuthFilter::isActive).ifPresent(user -> {
                    user.setLastSeenAt(Instant.now());
                    userRepository.save(user);
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                    var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Same rule login applies (AuthServiceImpl): an employee must be APPROVED.
     *  Checked on every request, not just at login, so un-approving an employee
     *  cuts off their existing token immediately instead of when it expires. */
    private static boolean isActive(User user) {
        return user.getRole() != Role.USER || user.getStatus() == AccountStatus.APPROVED;
    }
}
