package com.aivoice.lead.repository;

import com.aivoice.lead.entity.LeadConsent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadConsentRepository extends JpaRepository<LeadConsent, UUID> {

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByLeadIdAndRevokedAtIsNull(UUID leadId);

    List<LeadConsent> findByLeadIdAndRevokedAtIsNull(UUID leadId);
}
