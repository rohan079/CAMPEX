package com.aivoice.lead.repository;

import com.aivoice.lead.entity.SiteVisit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteVisitRepository extends JpaRepository<SiteVisit, UUID>, JpaSpecificationExecutor<SiteVisit> {

    List<SiteVisit> findByLeadIdOrderByScheduledAtDesc(UUID leadId);

    @Query("select v from SiteVisit v where v.reminderAt <= :due and v.reminderSentAt is null and v.status in ('SCHEDULED', 'CONFIRMED')")
    List<SiteVisit> findDueReminders(@Param("due") Instant due);
}
