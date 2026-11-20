package com.aivoice.lead.repository;

import com.aivoice.lead.entity.CampaignAnomaly;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignAnomalyRepository extends JpaRepository<CampaignAnomaly, UUID> {

    Optional<CampaignAnomaly> findFirstByClientIdAndDedupKeyAndStatusIn(UUID clientId, String dedupKey, Collection<String> statuses);

    Optional<CampaignAnomaly> findFirstByClientIdAndDedupKeyOrderByLastDetectedAtDesc(UUID clientId, String dedupKey);

    List<CampaignAnomaly> findTop200ByClientIdOrderByLastDetectedAtDesc(UUID clientId);
}
