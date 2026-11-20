package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ConversionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversionEventRepository extends JpaRepository<ConversionEvent, UUID>, JpaSpecificationExecutor<ConversionEvent> {

    Optional<ConversionEvent> findByClientIdAndDedupKey(UUID clientId, String dedupKey);

    Optional<ConversionEvent> findByIdAndClientId(UUID id, UUID clientId);

    List<ConversionEvent> findTop20ByClientIdAndVisitorIdOrderByEventTimeDesc(UUID clientId, String visitorId);

    @Query("select distinct e.visitorId from ConversionEvent e where e.leadId = :leadId and e.visitorId is not null")
    List<String> visitorIdsForLead(@Param("leadId") UUID leadId);

    @Modifying
    @Query("update ConversionEvent e set e.duplicateCount = e.duplicateCount + 1 where e.id = :id")
    void incrementDuplicates(@Param("id") UUID id);

    @Modifying
    @Query("update ConversionEvent e set e.leadId = :leadId where e.clientId = :clientId and e.visitorId = :visitorId and e.leadId is null")
    int linkVisitorToLead(@Param("clientId") UUID clientId, @Param("visitorId") String visitorId, @Param("leadId") UUID leadId);

    @Modifying
    @Query("update ConversionEvent e set e.clientIpAddress = null, e.clientUserAgent = null where e.receivedAt < :before and (e.clientIpAddress is not null or e.clientUserAgent is not null)")
    int purgeNetworkIdentifiers(@Param("before") Instant before);

    @Modifying
    @Query("delete from ConversionEvent e where e.receivedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
