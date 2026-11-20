package com.aivoice.lead.repository;

import com.aivoice.lead.entity.TrackingPixel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackingPixelRepository extends JpaRepository<TrackingPixel, UUID> {

    List<TrackingPixel> findByClientIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID clientId);

    Optional<TrackingPixel> findByIdAndClientIdAndDeletedAtIsNull(UUID id, UUID clientId);

    Optional<TrackingPixel> findByClientIdAndMetaPixelIdAndDeletedAtIsNull(UUID clientId, String metaPixelId);

    boolean existsByClientIdAndMetaPixelIdAndDeletedAtIsNull(UUID clientId, String metaPixelId);
}
