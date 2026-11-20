package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ClientAdAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientAdAccountRepository extends JpaRepository<ClientAdAccount, UUID> {

    List<ClientAdAccount> findByClientIdOrderByAdAccountIdAsc(UUID clientId);

    void deleteByClientId(UUID clientId);
}
