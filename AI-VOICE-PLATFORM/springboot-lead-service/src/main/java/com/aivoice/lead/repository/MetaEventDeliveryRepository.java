package com.aivoice.lead.repository;

import com.aivoice.lead.entity.MetaEventDelivery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaEventDeliveryRepository extends JpaRepository<MetaEventDelivery, UUID> {

    List<MetaEventDelivery> findByConversionEventIdOrderByAttemptNumberAsc(UUID conversionEventId);
}
