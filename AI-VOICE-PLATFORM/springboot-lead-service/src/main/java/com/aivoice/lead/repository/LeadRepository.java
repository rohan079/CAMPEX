package com.aivoice.lead.repository;

import com.aivoice.lead.entity.Lead;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

    Optional<Lead> findFirstByPhoneNumberInOrderByCreatedAtDesc(Collection<String> phoneNumbers);

    Optional<Lead> findFirstByClientIdAndPhoneNumberInOrderByCreatedAtDesc(UUID clientId, Collection<String> phoneNumbers);

    List<Lead> findTop6ByOrderByCreatedAtDesc();

    List<Lead> findTop6ByClientIdOrderByCreatedAtDesc(UUID clientId);

    List<Lead> findByClientId(UUID clientId);

    Optional<Lead> findByIdAndClientId(UUID id, UUID clientId);

    /** Duplicate candidates within one client: same phone (any stored form) or same email. */
    @Query("""
            select l from Lead l
            where l.clientId = :clientId and l.id <> :excludeId
              and (l.phoneNumber in :phones or (:email is not null and lower(l.email) = :email))
            order by l.createdAt asc
            """)
    List<Lead> findDuplicateCandidates(@Param("clientId") UUID clientId, @Param("excludeId") UUID excludeId,
                                       @Param("phones") Collection<String> phones, @Param("email") String email);

    @Query("select l.status as name, count(l) as total from Lead l group by l.status")
    List<NameCount> countByStatus();

    @Query("select l.status as name, count(l) as total from Lead l where l.clientId = :clientId group by l.status")
    List<NameCount> countByStatusForClient(@Param("clientId") UUID clientId);

    @Query("select l.preferredLanguage as name, count(l) as total from Lead l group by l.preferredLanguage")
    List<NameCount> countByLanguage();

    @Query("select l.preferredLanguage as name, count(l) as total from Lead l where l.clientId = :clientId group by l.preferredLanguage")
    List<NameCount> countByLanguageForClient(@Param("clientId") UUID clientId);

    long countByReviewStatus(String reviewStatus);

    long countByClientIdAndReviewStatus(UUID clientId, String reviewStatus);

    /** -2 is Hibernate's SKIP LOCKED, so two scheduler instances never queue the same lead twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select l from Lead l
            where l.status = 'awaiting_reply'
              and l.repliedAt is null
              and l.outreachSentAt is not null
              and l.source in ('ad_popup', 'ctwa')
              and l.followUpStart is not null
              and l.followUpStart <= :today
            """)
    List<Lead> findDueFollowUps(@Param("today") LocalDate today);
}
