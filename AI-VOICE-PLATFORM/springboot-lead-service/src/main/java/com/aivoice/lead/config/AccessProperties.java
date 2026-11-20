package com.aivoice.lead.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant access settings ({@code campex.access.*}). Platform admins may access every client; everyone else needs a
 * {@code client_members} row for the client.
 */
@ConfigurationProperties(prefix = "campex.access")
public record AccessProperties(List<String> platformAdminEmails) {

    public AccessProperties {
        platformAdminEmails = platformAdminEmails == null ? List.of() : platformAdminEmails.stream()
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .distinct()
                .toList();
    }

    public Set<String> adminSet() {
        return platformAdminEmails.stream().collect(Collectors.toUnmodifiableSet());
    }
}
