package com.aivoice.lead.repository;

import com.aivoice.lead.entity.CampaignExperiment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignExperimentRepository extends JpaRepository<CampaignExperiment, UUID> {

    List<CampaignExperiment> findByClientIdOrderByCreatedAtDesc(UUID clientId);
}
