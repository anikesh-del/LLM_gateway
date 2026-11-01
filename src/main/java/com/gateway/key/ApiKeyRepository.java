package com.gateway.key;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    /**
     * Atomically reserves the estimated cost against the key's budget.
     * Returns 1 if reservation succeeded; 0 if budget is exhausted or key is inactive.
     */
    @Modifying
    @Query(value = """
        UPDATE api_keys
        SET reserved_inr = reserved_inr + :estimatedCost,
            updated_at = NOW()
        WHERE id = :id
          AND is_active = TRUE
          AND (spent_inr + reserved_inr + :estimatedCost) <= budget_inr
    """, nativeQuery = true)
    int reserveBudget(@Param("id") UUID id, @Param("estimatedCost") BigDecimal estimatedCost);

    /**
     * Reconciles the in-flight reservation with actual expenditure reported by provider.
     */
    @Modifying
    @Query(value = """
        UPDATE api_keys
        SET spent_inr = spent_inr + :actualCost,
            reserved_inr = GREATEST(0, reserved_inr - :estimatedCost),
            updated_at = NOW()
        WHERE id = :id
    """, nativeQuery = true)
    int reconcileUsage(@Param("id") UUID id, @Param("estimatedCost") BigDecimal estimatedCost, @Param("actualCost") BigDecimal actualCost);

    /**
     * Releases the in-flight reservation on failure paths (timeout, 5xx, or network error).
     */
    @Modifying
    @Query(value = """
        UPDATE api_keys
        SET reserved_inr = GREATEST(0, reserved_inr - :estimatedCost),
            updated_at = NOW()
        WHERE id = :id
    """, nativeQuery = true)
    int releaseReservation(@Param("id") UUID id, @Param("estimatedCost") BigDecimal estimatedCost);
}
