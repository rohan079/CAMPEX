package com.aivoice.lead.repository;

import com.aivoice.lead.entity.MetaConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaConnectionRepository extends JpaRepository<MetaConnection, UUID> {

    Optional<MetaConnection> findByOwnerEmail(String ownerEmail);

    long deleteByOwnerEmail(String ownerEmail);
}
