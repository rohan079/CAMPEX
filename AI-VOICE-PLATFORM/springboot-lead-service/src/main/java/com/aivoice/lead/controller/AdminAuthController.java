package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.service.AdminSessionService;
import com.aivoice.lead.service.AdminSessionService.AdminIdentity;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.aivoice.lead.service.AdminSessionService.IssuedSession;
import com.aivoice.lead.service.FirebaseTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AdminAuthController {

    private final AdminSessionService sessions;
    private final FirebaseTokenVerifier firebaseTokens;
    private final AppProperties appProperties;

    public AdminAuthController(
            AdminSessionService sessions,
            FirebaseTokenVerifier firebaseTokens,
            AppProperties appProperties) {
        this.sessions = sessions;
        this.firebaseTokens = firebaseTokens;
        this.appProperties = appProperties;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 1024) String password) {
    }

    public record FirebaseLoginRequest(@NotBlank @Size(max = 4096) String idToken) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body) {
        try {
            return sessionResponse(sessions.login(body.email(), body.password()));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @PostMapping("/firebase")
    public ResponseEntity<Map<String, Object>> firebaseLogin(@Valid @RequestBody FirebaseLoginRequest body) {
        try {
            var user = firebaseTokens.verifyIdToken(body.idToken());
            return sessionResponse(sessions.loginWithVerifiedEmail(user.email()));
        } catch (AuthException e) {
            return error(e);
        }
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        if (!sessions.isConfigured()) {
            if (appProperties.isDevelopment()) {
                return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(Map.of("email", "local-development", "development", true));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("detail", "CAMPEX admin login is not configured"));
        }
        return sessions.authenticate(request)
                .map(identity -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(sessionBody(identity)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("detail", "CAMPEX admin login required")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request);
        ResponseCookie cookie = ResponseCookie.from(AdminSessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(!appProperties.isDevelopment())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    private ResponseEntity<Map<String, Object>> sessionResponse(IssuedSession session) {
        ResponseCookie cookie = ResponseCookie.from(AdminSessionService.COOKIE_NAME, session.token())
                .httpOnly(true)
                .secure(!appProperties.isDevelopment())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("email", session.ownerEmail(), "expires_at", session.expiresAt().toString()));
    }

    private static Map<String, Object> sessionBody(AdminIdentity identity) {
        return Map.of("email", identity.email(), "expires_at", identity.expiresAt().toString());
    }

    private static ResponseEntity<Map<String, Object>> error(AuthException e) {
        return ResponseEntity.status(e.status()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("detail", e.getMessage()));
    }
}
