package com.gateway.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.key.ApiKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@Service
public class BudgetService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;
    private final BigDecimal usdToInr;
    private final int defaultMaxTokens;
    private final int maxTokensCeiling;

    public record ModelPricing(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {}

    // In-memory lookup map loaded with model pricing tiers; fallback to 'default' if model unknown
    private final Map<String, ModelPricing> pricingMap = Map.of(
            "llama-3.1-8b-instant", new ModelPricing(new BigDecimal("0.05"), new BigDecimal("0.08")),
            "llama-3.3-70b-versatile", new ModelPricing(new BigDecimal("0.59"), new BigDecimal("0.79")),
            "openai/gpt-oss-20b", new ModelPricing(new BigDecimal("0.10"), new BigDecimal("0.20")),
            "gemini-1.5-flash", new ModelPricing(new BigDecimal("0.075"), new BigDecimal("0.30")),
            "gemini-2.0-flash", new ModelPricing(new BigDecimal("0.10"), new BigDecimal("0.40")),
            "gemini-3.8-flash", new ModelPricing(new BigDecimal("0.10"), new BigDecimal("0.40")),
            "gpt-4o-mini", new ModelPricing(new BigDecimal("0.15"), new BigDecimal("0.60")),
            "gpt-4o", new ModelPricing(new BigDecimal("2.50"), new BigDecimal("10.00")),
            "default", new ModelPricing(new BigDecimal("0.50"), new BigDecimal("1.50"))
    );

    public BudgetService(
            ApiKeyRepository apiKeyRepository,
            ObjectMapper objectMapper,
            @Value("${gateway.budget.usd-to-inr:84.00}") BigDecimal usdToInr,
            @Value("${gateway.budget.default-max-tokens:512}") int defaultMaxTokens,
            @Value("${gateway.budget.max-tokens-ceiling:4096}") int maxTokensCeiling
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.objectMapper = objectMapper;
        this.usdToInr = usdToInr;
        this.defaultMaxTokens = defaultMaxTokens;
        this.maxTokensCeiling = maxTokensCeiling;
    }

    public record CostEstimate(
            String model,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            BigDecimal estimatedCostInr
    ) {}

    /**
     * Estimates pre-call request cost using character heuristics and configured ceilings.
     */
    public CostEstimate estimateCost(String requestBodyJson) {
        String model = "gpt-4o-mini";
        int estInputTokens = 10;
        int estOutputTokens = defaultMaxTokens;

        if (requestBodyJson != null && !requestBodyJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(requestBodyJson);
                if (root.hasNonNull("model")) {
                    model = root.get("model").asText().trim().toLowerCase();
                }

                if (root.has("messages") && root.get("messages").isArray()) {
                    int charCount = 0;
                    for (JsonNode msg : root.get("messages")) {
                        if (msg.hasNonNull("content")) {
                            charCount += msg.get("content").asText().length();
                        }
                    }
                    // English text averages ~4 characters per token
                    estInputTokens = Math.max(1, (int) Math.ceil(charCount / 4.0));
                }

                if (root.hasNonNull("max_tokens")) {
                    int requested = root.get("max_tokens").asInt();
                    estOutputTokens = Math.min(Math.max(1, requested), maxTokensCeiling);
                } else if (root.hasNonNull("max_completion_tokens")) {
                    int requested = root.get("max_completion_tokens").asInt();
                    estOutputTokens = Math.min(Math.max(1, requested), maxTokensCeiling);
                }
            } catch (Exception e) {
                // Fall back gracefully to conservative defaults if JSON parsing fails
            }
        }

        BigDecimal costInr = calculateCostInr(model, estInputTokens, estOutputTokens);
        return new CostEstimate(model, estInputTokens, estOutputTokens, costInr);
    }

    /**
     * Computes cost in INR using the formula:
     * Total Cost = ((InputTokens * P_i / 1,000,000) + (OutputTokens * P_o / 1,000,000)) * USD_INR
     */
    public BigDecimal calculateCostInr(String model, int inputTokens, int outputTokens) {
        ModelPricing pricing = pricingMap.getOrDefault(
                model != null ? model.toLowerCase() : "default",
                pricingMap.get("default")
        );

        BigDecimal inputCostUsd = BigDecimal.valueOf(inputTokens)
                .multiply(pricing.inputPerMillionUsd())
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);

        BigDecimal outputCostUsd = BigDecimal.valueOf(outputTokens)
                .multiply(pricing.outputPerMillionUsd())
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);

        BigDecimal totalCostUsd = inputCostUsd.add(outputCostUsd);
        return totalCostUsd.multiply(usdToInr).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Atomically reserves budget in a single conditional SQL update.
     * Returns true if reservation succeeded; false if budget exceeded or key inactive.
     */
    @Transactional
    public boolean reserve(UUID keyId, BigDecimal estimatedCostInr) {
        int rowsUpdated = apiKeyRepository.reserveBudget(keyId, estimatedCostInr);
        return rowsUpdated == 1;
    }

    /**
     * Reconciles actual token cost: increments spent_inr and releases reserved_inr.
     */
    @Transactional
    public void reconcile(UUID keyId, BigDecimal estimatedCostInr, BigDecimal actualCostInr) {
        apiKeyRepository.reconcileUsage(keyId, estimatedCostInr, actualCostInr);
    }

    /**
     * Releases reservation on any failure path (timeout, 5xx, cancellation).
     */
    @Transactional
    public void releaseReservation(UUID keyId, BigDecimal estimatedCostInr) {
        apiKeyRepository.releaseReservation(keyId, estimatedCostInr);
    }
}

