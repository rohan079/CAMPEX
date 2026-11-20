package com.aivoice.lead.repository;

import com.aivoice.lead.entity.PerformanceRecommendation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRecommendationRepository extends JpaRepository<PerformanceRecommendation, UUID> {

    Optional<PerformanceRecommendation> findByClientIdAndDedupKeyAndStatus(UUID clientId, String dedupKey, String status);

    List<PerformanceRecommendation> findTop200ByClientIdOrderByCreatedAtDesc(UUID clientId);

    @Modifying
    @Query("update PerformanceRecommendation r set r.status = 'expired' where r.status = 'pending' and r.expiresAt < :now")
    int expireOverdue(@Param("now") Instant now);
}
