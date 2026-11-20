package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadFollowUp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadFollowUpRepository extends JpaRepository<LeadFollowUp, UUID> {

    List<LeadFollowUp> findByLeadIdOrderByDueAtAsc(UUID leadId);

    List<LeadFollowUp> findByClientIdAndStatusAndDueAtBeforeOrderByDueAtAsc(UUID clientId, String status, Instant before);

    @Query("select f from LeadFollowUp f where f.status = 'pending' and f.reminderSentAt is null and f.dueAt <= :due")
    List<LeadFollowUp> findDueReminders(@Param("due") Instant due);
}
