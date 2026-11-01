package com.gateway.usage;

import com.gateway.key.ApiKey;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_logs")
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false, updatable = false)
    private ApiKey apiKey;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_inr", nullable = false, precision = 14, scale = 6)
    private BigDecimal costInr;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UsageLog() {}

    public UsageLog(
            ApiKey apiKey,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal costInr,
            String provider,
            String status
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costInr = costInr;
        this.provider = provider;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getCostInr() {
        return costInr;
    }

    public String getProvider() {
        return provider;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

