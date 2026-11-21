package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignExecutionDtos.NotificationDto;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications, one row per recipient. Recipients are the client's owners and managers plus the user who
 * triggered the event. The {@code channel} column keeps room for email/WhatsApp delivery later.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;
    private final ClientAccessService access;

    public NotificationService(JdbcTemplate jdbc, ClientAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional
    public void notifyClientManagers(UUID clientId, String actorEmail, String type, String severity, String title,
                                     String body, String entityType, Object entityId) {
        Set<String> recipients = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT user_email FROM client_members WHERE client_id = ? AND role IN ('owner', 'manager')",
                String.class, clientId));
        if (actorEmail != null && !actorEmail.isBlank()) {
            recipients.add(actorEmail);
        }
        Timestamp now = Timestamp.from(Instant.now());
        for (String recipient : recipients) {
            jdbc.update("INSERT INTO notifications (id, client_id, recipient_email, type, severity, title, body, "
                            + "entity_type, entity_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), clientId, recipient, type, severity, truncate(title, 200),
                    truncate(body, 1000), entityType, entityId == null ? null : String.valueOf(entityId), now);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> mine(boolean unreadOnly, int limit) {
        String email = access.currentActor().email();
        return jdbc.query("SELECT * FROM notifications WHERE recipient_email = ?"
                        + (unreadOnly ? " AND read_at IS NULL" : "") + " ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> new NotificationDto(rs.getString("id"), rs.getString("client_id"), rs.getString("type"),
                        rs.getString("severity"), rs.getString("title"), rs.getString("body"),
                        rs.getString("entity_type"), rs.getString("entity_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
                email, Math.max(1, Math.min(limit, 200)));
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_email = ? AND read_at IS NULL",
                Long.class, access.currentActor().email());
        return n == null ? 0 : n;
    }

    @Transactional
    public void markRead(UUID id) {
        int updated = jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE id = ? AND recipient_email = ?",
                Timestamp.from(Instant.now()), id, access.currentActor().email());
        if (updated == 0) {
            throw new NotFoundException("Notification not found");
        }
    }

    @Transactional
    public int markAllRead() {
        return jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_email = ? AND read_at IS NULL",
                Timestamp.from(Instant.now()), access.currentActor().email());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
