package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadScoringConfig;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadScoringConfigRepository extends JpaRepository<LeadScoringConfig, UUID> {
}
