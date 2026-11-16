package com.aivoice.lead.repository;

import com.aivoice.lead.entity.VoiceCall;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceCallRepository extends JpaRepository<VoiceCall, UUID>, JpaSpecificationExecutor<VoiceCall> {

    List<VoiceCall> findByLeadId(UUID leadId);

    List<VoiceCall> findByLeadIdIn(Collection<UUID> leadIds);

    Optional<VoiceCall> findByOpenaiSessionId(String sessionId);

    List<VoiceCall> findByStatusInAndCreatedAtBefore(Collection<String> statuses, java.time.Instant before);

    boolean existsByLeadIdAndStatusIn(UUID leadId, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);

    List<VoiceCall> findTop6ByOrderByCreatedAtDesc();

    @Query("select c.status as name, count(c) as total from VoiceCall c group by c.status")
    List<NameCount> countByStatus();

    @Query("select c.status as name, count(c) as total from VoiceCall c "
            + "where c.leadId in (select l.id from Lead l where l.clientId = :clientId) group by c.status")
    List<NameCount> countByStatusForClient(@Param("clientId") UUID clientId);

    @Query("select c from VoiceCall c where c.leadId in (select l.id from Lead l where l.clientId = :clientId) "
            + "order by c.createdAt desc")
    List<VoiceCall> recentForClient(@Param("clientId") UUID clientId, org.springframework.data.domain.Pageable page);

    /** Takes the oldest queued call and skips rows another worker already holds. Call inside a transaction. */
    @Query(value = """
            select * from voice_calls
            where status = 'queued'
            order by created_at
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<VoiceCall> claimNextQueued();

    @Modifying(flushAutomatically = true)
    @Query("update VoiceCall c set c.status = 'cancelled' where c.leadId = :leadId and c.status = 'queued'")
    int cancelQueued(@Param("leadId") UUID leadId);

    @Modifying(flushAutomatically = true)
    @Query("update VoiceCall c set c.language = :language where c.leadId = :leadId and c.status = 'queued'")
    int updateQueuedLanguage(@Param("leadId") UUID leadId, @Param("language") String language);
}
