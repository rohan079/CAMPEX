package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ClientBudgetPolicy;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientBudgetPolicyRepository extends JpaRepository<ClientBudgetPolicy, UUID> {
}
