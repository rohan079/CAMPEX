package com.aivoice.lead.service;

import com.aivoice.lead.entity.AuditEvent;
import com.aivoice.lead.repository.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the append-only audit trail. Callers must pass only non-secret, non-personal change details. */
@Service
public class AuditService {

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID clientId, String actorEmail, String entityType, Object entityId, String action,
                       Map<String, Object> changes) {
        AuditEvent event = new AuditEvent();
        event.setClientId(clientId);
        event.setActorEmail(actorEmail);
        event.setEntityType(entityType);
        event.setEntityId(String.valueOf(entityId));
        event.setAction(action);
        event.setChanges(changes);
        events.save(event);
    }
}
