package com.aivoice.lead.repository;

import com.aivoice.lead.entity.CreativeAnalysis;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreativeAnalysisRepository extends JpaRepository<CreativeAnalysis, UUID> {

    List<CreativeAnalysis> findTop50ByClientIdOrderByCreatedAtDesc(UUID clientId);
}
