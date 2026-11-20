package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadAssignmentRepository extends JpaRepository<LeadAssignment, UUID> {

    List<LeadAssignment> findByLeadIdOrderByAssignedAtDesc(UUID leadId);

    Optional<LeadAssignment> findFirstByLeadIdAndUnassignedAtIsNullOrderByAssignedAtDesc(UUID leadId);
}
