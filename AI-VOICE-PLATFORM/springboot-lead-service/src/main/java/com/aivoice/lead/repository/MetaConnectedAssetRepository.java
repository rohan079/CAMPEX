package com.aivoice.lead.repository;

import com.aivoice.lead.entity.MetaConnectedAsset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaConnectedAssetRepository extends JpaRepository<MetaConnectedAsset, UUID> {

    List<MetaConnectedAsset> findAllByConnectionIdOrderByAssetTypeAscNameAsc(UUID connectionId);

    void deleteAllByConnectionId(UUID connectionId);
}
