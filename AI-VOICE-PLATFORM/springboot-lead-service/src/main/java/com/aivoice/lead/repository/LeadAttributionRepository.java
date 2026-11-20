package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadAttribution;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadAttributionRepository extends JpaRepository<LeadAttribution, UUID> {

    Optional<LeadAttribution> findByLeadId(UUID leadId);

    /** Row lock so concurrent touches for the same lead cannot both think they are first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LeadAttribution a where a.leadId = :leadId")
    Optional<LeadAttribution> lockByLeadId(@Param("leadId") UUID leadId);
}
