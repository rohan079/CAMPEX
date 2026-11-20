package com.aivoice.lead.repository;

import com.aivoice.lead.entity.PerformanceSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceSnapshotRepository extends JpaRepository<PerformanceSnapshot, UUID> {

    List<PerformanceSnapshot> findByClientIdAndLevelAndDayBetweenOrderByDayAsc(UUID clientId, String level, LocalDate from, LocalDate to);
}
