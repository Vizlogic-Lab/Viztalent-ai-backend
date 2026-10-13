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
import java.util.Set;

/** Reads "Authorization: Bearer <token>", resolves it to a User, and — while
 *  we're already touching the row — bumps last_seen_at so the Employees
 *  page's online/offline dots stay accurate without a dedicated heartbeat
 *  endpoint. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** The only calls allowed until a must_change_password account has changed it. */
    static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE =
            Set.of("/api/auth/me", "/api/auth/change_password", "/api/auth/logout");
    private static final String PASSWORD_CHANGE_REQUIRED = "{\"ok\":false,"
            + "\"message\":\"Change your password before using the app.\",\"code\":\"PASSWORD_CHANGE_REQUIRED\","
            + "\"detail\":\"Change your password before using the app.\"}";

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
            Optional<JwtService.VerifiedToken> verified = jwtService.verify(token);
            if (verified.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                int tokenVersion = verified.get().tokenVersion();
                userRepository.findById(verified.get().userId())
                        .filter(JwtAuthFilter::isActive)
                        // A password change bumps the version: older tokens are signed out.
                        .filter(user -> user.getTokenVersion() == tokenVersion)
                        .ifPresent(user -> {
                            user.setLastSeenAt(Instant.now());
                            userRepository.save(user);
                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                            var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        });
            }
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof User user
                && user.isMustChangePassword()
                && !ALLOWED_BEFORE_PASSWORD_CHANGE.contains(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(PASSWORD_CHANGE_REQUIRED);
            return;
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
