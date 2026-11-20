package com.aivoice.lead.repository;

import com.aivoice.lead.entity.Sale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SaleRepository extends JpaRepository<Sale, UUID>, JpaSpecificationExecutor<Sale> {

    Optional<Sale> findByIdAndDeletedAtIsNull(UUID id);

    List<Sale> findByLeadIdAndDeletedAtIsNullOrderBySoldAtDesc(UUID leadId);
}
