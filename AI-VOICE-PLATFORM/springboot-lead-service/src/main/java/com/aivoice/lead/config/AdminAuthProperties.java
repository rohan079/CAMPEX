package com.aivoice.lead.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campex.admin")
public record AdminAuthProperties(
        String email,
        String passwordHash,
        long sessionTtlSeconds) {

    public AdminAuthProperties {
        sessionTtlSeconds = sessionTtlSeconds < 300 ? 28_800 : Math.min(sessionTtlSeconds, 86_400);
    }

    public boolean isConfigured() {
        return email != null && !email.isBlank() && PasswordHash.isValid(passwordHash);
    }

    private static final class PasswordHash {
        private static boolean isValid(String encoded) {
            if (encoded == null) return false;
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
                byte[] hash = Base64.getUrlDecoder().decode(parts[3]);
                return iterations >= 100_000 && iterations <= 2_000_000 && salt.length >= 16 && hash.length == 32;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
