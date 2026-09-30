package com.smartstaff.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.response.ApiErrorResponse;
import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import com.smartstaff.service.DownloadSignatureService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** For GET requests on the download paths that carry a signature
 *  (?exp&uid&sig): a valid signature authenticates the request as the user
 *  who signed it; an invalid or expired one is refused with 403. Requests
 *  without a signature pass through to normal JWT authentication. */
@Component
public class DownloadSignatureFilter extends OncePerRequestFilter {

    public static final List<Pattern> SIGNABLE_PATHS = List.of(
            Pattern.compile("^/api/jd/[0-9a-fA-F-]{36}/download$"),
            Pattern.compile("^/api/resumes/[0-9a-fA-F-]{36}/download/[^/]+$"),
            Pattern.compile("^/api/download_report$"),
            Pattern.compile("^/api/scorecard/[0-9a-fA-F-]{36}/[^/]+$")
    );

    private final DownloadSignatureService signatureService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DownloadSignatureFilter(DownloadSignatureService signatureService, UserRepository userRepository,
                                   ObjectMapper objectMapper) {
        this.signatureService = signatureService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public static boolean isSignable(String path) {
        return path != null && SIGNABLE_PATHS.stream().anyMatch(p -> p.matcher(path).matches());
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || request.getParameter("sig") == null
                || !isSignable(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Optional<User> user = signatureService.verify(request.getRequestURI(), request.getParameter("exp"),
                        request.getParameter("uid"), request.getParameter("sig"))
                .flatMap(this::activeUser);
        if (user.isEmpty()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiErrorResponse.of("This download link has expired or is invalid.")));
            return;
        }
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.get(), null, authorities));
        chain.doFilter(request, response);
    }

    private Optional<User> activeUser(UUID id) {
        return userRepository.findById(id)
                .filter(u -> u.getRole() != Role.USER || u.getStatus() == AccountStatus.APPROVED);
    }
}
