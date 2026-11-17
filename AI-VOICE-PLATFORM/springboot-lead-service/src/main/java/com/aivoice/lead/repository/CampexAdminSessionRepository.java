package com.aivoice.lead.repository;

import com.aivoice.lead.entity.CampexAdminSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampexAdminSessionRepository extends JpaRepository<CampexAdminSession, UUID> {

    Optional<CampexAdminSession> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    long deleteByTokenHash(String tokenHash);

    long deleteByExpiresAtBefore(Instant now);
}
