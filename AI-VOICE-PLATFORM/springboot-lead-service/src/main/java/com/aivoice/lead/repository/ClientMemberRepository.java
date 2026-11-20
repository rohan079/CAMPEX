package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ClientMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientMemberRepository extends JpaRepository<ClientMember, UUID> {

    Optional<ClientMember> findByClientIdAndUserEmail(UUID clientId, String userEmail);

    boolean existsByClientIdAndUserEmail(UUID clientId, String userEmail);

    List<ClientMember> findByUserEmail(String userEmail);

    List<ClientMember> findByClientIdOrderByUserEmailAsc(UUID clientId);
}
