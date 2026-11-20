package com.aivoice.lead.repository;

import com.aivoice.lead.entity.SalesAgent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesAgentRepository extends JpaRepository<SalesAgent, UUID> {

    List<SalesAgent> findByClientIdOrderByNameAsc(UUID clientId);

    Optional<SalesAgent> findByIdAndClientId(UUID id, UUID clientId);
}
