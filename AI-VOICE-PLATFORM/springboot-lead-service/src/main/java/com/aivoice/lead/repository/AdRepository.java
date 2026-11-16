package com.aivoice.lead.repository;

import com.aivoice.lead.entity.Ad;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdRepository extends JpaRepository<Ad, UUID> {

    Optional<Ad> findByExternalId(String externalId);

    List<Ad> findByClientIdOrderByCreatedAtDesc(UUID clientId);
}
