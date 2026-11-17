package com.aivoice.lead.config;

import com.aivoice.lead.service.AdminSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Checks the popup and internal service keys; the dashboard uses a CAMPEX admin session. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final AdminSessionService adminSessions;

    public ApiKeyFilter(AppProperties props, AdminSessionService adminSessions) {
        this.props = props;
        this.adminSessions = adminSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/internal/")) {
            if (!allowed(request, "X-Service-Key", props.internalServiceKey(), response, "Service key")) {
                return;
            }
        } else if (path.equals("/api/ad-leads")) {
            if (!allowed(request, "X-Ad-Popup-Token", props.adPopupToken(), response, "Ad popup token")) {
                return;
            }
        } else if (path.startsWith("/api/")) {
            if (!adminSessions.isConfigured()) {
                if (!props.isDevelopment()) {
                    reject(response, 503, "CAMPEX admin authentication is not configured");
                    return;
                }
            } else if (adminSessions.authenticate(request).isEmpty()) {
                reject(response, 401, "CAMPEX admin login required");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(HttpServletRequest request, String header, String expected,
                            HttpServletResponse response, String label) throws IOException {
        if (expected == null || expected.isBlank()) {
            if (props.isDevelopment()) {
                return true;
            }
            reject(response, 503, label + " is not configured");
            return false;
        }
        String supplied = request.getHeader(header);
        if (supplied == null || !constantTimeEquals(supplied, expected)) {
            reject(response, 401, "Invalid " + label.toLowerCase());
            return false;
        }
        return true;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
