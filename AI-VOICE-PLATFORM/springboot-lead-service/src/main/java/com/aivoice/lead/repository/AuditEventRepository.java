package com.aivoice.lead.repository;

import com.aivoice.lead.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findTop50ByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}
