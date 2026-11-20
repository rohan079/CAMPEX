package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ClientProject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientProjectRepository extends JpaRepository<ClientProject, UUID> {

    List<ClientProject> findByClientIdOrderByNameAsc(UUID clientId);

    Optional<ClientProject> findByIdAndClientId(UUID id, UUID clientId);
}
