package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadAttributionTouch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadAttributionTouchRepository extends JpaRepository<LeadAttributionTouch, UUID> {

    List<LeadAttributionTouch> findByLeadIdOrderByTouchedAtAsc(UUID leadId);
}
