package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {

    List<LeadActivity> findTop200ByLeadIdOrderByOccurredAtDesc(UUID leadId);
}
