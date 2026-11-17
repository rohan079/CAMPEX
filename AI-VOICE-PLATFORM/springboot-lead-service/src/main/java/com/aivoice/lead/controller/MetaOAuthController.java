package com.aivoice.lead.controller;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.aivoice.lead.service.DashboardAuthService;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.MetaConnectionStore.ConnectionStatus;
import com.aivoice.lead.service.MetaOAuthService;
import com.aivoice.lead.service.MetaOAuthService.MissingPermissionsException;
import com.aivoice.lead.service.MetaOAuthStateService;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/oauth/meta")
public class MetaOAuthController {

    private static final Logger log = LoggerFactory.getLogger(MetaOAuthController.class);
    private final DashboardAuthService dashboardAuth;
    private final MetaOAuthStateService states;
    private final MetaOAuthService meta;
    private final MetaOAuthProperties properties;

    public MetaOAuthController(
            DashboardAuthService dashboardAuth,
            MetaOAuthStateService states,
            MetaOAuthService meta,
            MetaOAuthProperties properties) {
        this.dashboardAuth = dashboardAuth;
        this.states = states;
        this.meta = meta;
        this.properties = properties;
    }

    @GetMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> loginJson(jakarta.servlet.http.HttpServletRequest request) {
        try {
            URI authorizationUri = startAuthorization(request);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("authorization_url", authorizationUri.toString()));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status())
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("detail", "Meta OAuth is not fully configured on the server"));
        }
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login(jakarta.servlet.http.HttpServletRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(startAuthorization(request))
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (AuthException e) {
            return redirect("error", "login_required");
        } catch (IllegalStateException e) {
            return redirect("error", "configuration");
        }
    }

    private URI startAuthorization(jakarta.servlet.http.HttpServletRequest request) {
        String ownerEmail = dashboardAuth.requireOwnerEmail(request);
        if (!meta.isConfigured()) {
            throw new IllegalStateException("Meta OAuth is not fully configured on the server");
        }
        return meta.authorizationUri(states.create(ownerEmail));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        final String ownerEmail;
        try {
            ownerEmail = states.consume(state);
        } catch (ResponseStatusException e) {
            return redirect("error", "invalid_state");
        }

        if (error != null) {
            return redirect("error", "access_denied".equals(error) ? "access_denied" : "provider_error");
        }
        if (code == null || code.isBlank()) return redirect("error", "missing_code");

        try {
            meta.complete(ownerEmail, code);
            return redirect("connected", null);
        } catch (MissingPermissionsException e) {
            log.warn("Meta OAuth permissions were not granted for admin {}", ownerEmail);
            return redirect("error", "permissions");
        } catch (MetaGraphException e) {
            log.warn("Meta Graph OAuth exchange failed for admin {} (HTTP {}, code {})",
                    ownerEmail, e.httpStatus(), e.graphCode());
            return redirect("error", e.graphCode() == 190 ? "expired_token" : "provider_error");
        } catch (IllegalStateException e) {
            log.error("Meta OAuth setup or token encryption failed for admin {}", ownerEmail);
            return redirect("error", "configuration");
        } catch (IllegalArgumentException e) {
            log.warn("Meta OAuth callback was invalid for admin {}", ownerEmail);
            return redirect("error", "invalid_response");
        }
    }

    @GetMapping("/status")
    public ConnectionStatus status(jakarta.servlet.http.HttpServletRequest request) {
        return meta.status(dashboardAuth.requireOwnerEmail(request));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(jakarta.servlet.http.HttpServletRequest request) {
        meta.disconnect(dashboardAuth.requireOwnerEmail(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<Void> redirect(String result, String reason) {
        try {
            URI frontend = URI.create(properties.frontendRedirectUrl());
            if (!"https".equalsIgnoreCase(frontend.getScheme()) || frontend.getHost() == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(frontend)
                    .replaceQueryParam("meta", result)
                    .replaceQueryParam("reason");
            if (reason != null) builder.queryParam("reason", reason);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(builder.build().encode().toUri())
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
