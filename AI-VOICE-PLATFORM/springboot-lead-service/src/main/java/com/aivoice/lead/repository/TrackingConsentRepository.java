package com.aivoice.lead.repository;

import com.aivoice.lead.entity.TrackingConsent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackingConsentRepository extends JpaRepository<TrackingConsent, UUID> {

    Optional<TrackingConsent> findFirstByClientIdAndVisitorIdOrderByRecordedAtDesc(UUID clientId, String visitorId);

    @Modifying
    @Query("delete from TrackingConsent c where c.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
