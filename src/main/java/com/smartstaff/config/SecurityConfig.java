package com.smartstaff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.response.ApiErrorResponse;
import com.smartstaff.filter.RateLimitFilter;
import com.smartstaff.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper objectMapper;
    private final String allowedOrigins;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            RateLimitFilter rateLimitFilter,
            ObjectMapper objectMapper,
            @Value("${app.cors.allowed-origins}") String allowedOrigins
    ) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
    }

    /** RateLimitFilter is a @Component (so it can take @Value config), which
     *  makes Spring Boot also register it as a bare servlet filter. It belongs
     *  only inside the security chain — see filterChain() — so switch that
     *  second registration off. */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: login/signup, and the candidate-facing token-based
                        // interview endpoints (the token itself is the credential).
                        .requestMatchers("/api/auth/login", "/api/auth/signup").permitAll()
                        .requestMatchers("/api/interview/by_token/**", "/api/interview/save_by_token/**").permitAll()
                        // Public: Twilio's own webhooks (no bearer token — Twilio can't send
                        // one). Gated instead by X-Twilio-Signature — see TwilioWebhookController.
                        .requestMatchers("/api/interview/twiml/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // File downloads the frontend opens as plain <a href target="_blank">
                        // links (Jobs.jsx, Candidates.jsx, Dashboard.jsx, Settings.jsx) —
                        // never through axios, so no bearer token ever reaches these.
                        // Security here relies on the path containing an unguessable job/
                        // resume UUID rather than a session check; revisit with signed URLs
                        // if stricter access control is needed later (see docs/FEATURES.md).
                        .requestMatchers(HttpMethod.GET, "/api/jd/*/download", "/api/resumes/**", "/api/download_report", "/api/scorecard/**").permitAll()
                        // Admin-only areas are decided here, in the filter chain, so a non-admin is
                        // refused (403) before the request body is even parsed or validated — without
                        // this, an employee sending an invalid body got a 400 instead. The
                        // @PreAuthorize annotations on the controllers stay as a second lock.
                        .requestMatchers("/api/config", "/api/config/**", "/api/reset").hasRole("ADMIN")
                        .requestMatchers("/api/auth/accounts", "/api/auth/approve").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/questions/upload", "/api/questions/clear").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/questions/upload/*").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) -> writeJsonError(response, HttpStatus.UNAUTHORIZED, "Authentication required."))
                        .accessDeniedHandler((request, response, ex) -> writeJsonError(response, HttpStatus.FORBIDDEN, "You don't have permission to do that."))
                )
                // Right after CORS so a 429 still carries the CORS headers (see RateLimitFilter).
                .addFilterAfter(rateLimitFilter, CorsFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJsonError(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiErrorResponse.of(message)));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // So the frontend can read them off a response (e.g. quote the request id in a bug report).
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
