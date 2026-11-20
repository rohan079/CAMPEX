package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadQualification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadQualificationRepository extends JpaRepository<LeadQualification, UUID> {

    Optional<LeadQualification> findByLeadId(UUID leadId);
}
