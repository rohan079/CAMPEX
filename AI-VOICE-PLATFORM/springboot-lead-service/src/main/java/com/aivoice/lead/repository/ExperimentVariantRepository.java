package com.aivoice.lead.repository;

import com.aivoice.lead.entity.ExperimentVariant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, UUID> {

    List<ExperimentVariant> findByExperimentIdOrderByRoleAsc(UUID experimentId);
}
