package com.aivoice.lead.repository;

import com.aivoice.lead.entity.RecommendationEvidence;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationEvidenceRepository extends JpaRepository<RecommendationEvidence, UUID> {

    List<RecommendationEvidence> findByRecommendationIdIn(Collection<UUID> ids);
}
