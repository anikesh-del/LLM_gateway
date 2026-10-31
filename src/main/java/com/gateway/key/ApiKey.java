package com.gateway.key;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "budget_inr", nullable = false, precision = 14, scale = 6)
    private BigDecimal budgetInr;

    @Column(name = "spent_inr", nullable = false, precision = 14, scale = 6)
    private BigDecimal spentInr = BigDecimal.ZERO;

    @Column(name = "reserved_inr", nullable = false, precision = 14, scale = 6)
    private BigDecimal reservedInr = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ApiKey() {
    }

    public ApiKey(String keyHash, String name, BigDecimal budgetInr) {
        this.keyHash = keyHash;
        this.name = name;
        this.budgetInr = budgetInr;
        this.spentInr = BigDecimal.ZERO;
        this.reservedInr = BigDecimal.ZERO;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBudgetInr() {
        return budgetInr;
    }

    public BigDecimal getSpentInr() {
        return spentInr;
    }

    public BigDecimal getReservedInr() {
        return reservedInr;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }
}

