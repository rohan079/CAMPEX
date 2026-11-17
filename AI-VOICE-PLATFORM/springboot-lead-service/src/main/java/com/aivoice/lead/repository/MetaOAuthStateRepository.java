package com.aivoice.lead.repository;

import com.aivoice.lead.entity.MetaOAuthState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface MetaOAuthStateRepository extends JpaRepository<MetaOAuthState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from MetaOAuthState state where state.stateHash = :stateHash")
    Optional<MetaOAuthState> lockByStateHash(@Param("stateHash") String stateHash);

    void deleteByExpiresAtBefore(java.time.Instant now);
}
