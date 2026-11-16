package com.aivoice.lead.repository;

import com.aivoice.lead.entity.Client;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    Optional<Client> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Client> findAllByOrderByNameAsc();
}
