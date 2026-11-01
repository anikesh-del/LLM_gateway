package com.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.auth.ApiKeyAuthenticationFilter;
import com.gateway.budget.BudgetService;
import com.gateway.key.ApiKey;
import com.gateway.provider.LlmProviderService;
import com.gateway.usage.UsageLog;
import com.gateway.usage.UsageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final LlmProviderService llmProviderService;
    private final BudgetService budgetService;
    private final UsageLogRepository usageLogRepository;
    private final ObjectMapper objectMapper;

    public ChatCompletionController(
            LlmProviderService llmProviderService,
            BudgetService budgetService,
            UsageLogRepository usageLogRepository,
            ObjectMapper objectMapper
    ) {
        this.llmProviderService = llmProviderService;
        this.budgetService = budgetService;
        this.usageLogRepository = usageLogRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(
            @RequestBody String requestBody,
            HttpServletRequest request
    ) {
        ApiKey apiKey = (ApiKey) request.getAttribute(ApiKeyAuthenticationFilter.AUTHENTICATED_KEY_ATTR);
        if (apiKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Unauthenticated gateway request\"}");
        }

        UUID keyId = apiKey.getId();

        // 1. Pre-call cost estimation
        BudgetService.CostEstimate estimate = budgetService.estimateCost(requestBody);

        // 2. Atomic check-and-reserve in PostgreSQL
        boolean reserved = budgetService.reserve(keyId, estimate.estimatedCostInr());
        if (!reserved) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format(
                            "{\"error\":\"Budget exceeded\",\"model\":\"%s\",\"estimatedCostInr\":%s,\"message\":\"Insufficient budget remaining on virtual API key\"}",
                            estimate.model(),
                            estimate.estimatedCostInr()
                    ));
        }

        // 3. Dispatch to upstream provider
        ResponseEntity<String> response;
        try {
            response = llmProviderService.forwardChatCompletion(requestBody);
        } catch (Exception e) {
            budgetService.releaseReservation(keyId, estimate.estimatedCostInr());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to communicate with upstream provider\"}");
        }

        // 4. Handle provider response
        if (response.getStatusCode().is2xxSuccessful()) {
            int promptTokens = estimate.estimatedInputTokens();
            int completionTokens = estimate.estimatedOutputTokens();

            try {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("usage")) {
                    JsonNode usage = root.get("usage");
                    if (usage.hasNonNull("prompt_tokens")) {
                        promptTokens = usage.get("prompt_tokens").asInt();
                    }
                    if (usage.hasNonNull("completion_tokens")) {
                        completionTokens = usage.get("completion_tokens").asInt();
                    }
                }
            } catch (Exception ignored) {}

            BigDecimal actualCostInr = budgetService.calculateCostInr(
                    estimate.model(),
                    promptTokens,
                    completionTokens
            );

            // Reconcile budget
            budgetService.reconcile(keyId, estimate.estimatedCostInr(), actualCostInr);

            String providerUsed = response.getHeaders().getFirst(LlmProviderService.PROVIDER_USED_HEADER);
            if (providerUsed == null || providerUsed.isBlank()) {
                providerUsed = "primary";
            }

            // Synchronous audit logging
            try {
                UsageLog usageLog = new UsageLog(
                        apiKey,
                        estimate.model(),
                        promptTokens,
                        completionTokens,
                        actualCostInr,
                        providerUsed,
                        "SUCCESS"
                );
                usageLogRepository.save(usageLog);
            } catch (Exception e) {
                // Return gateway error rather than claiming successful completion
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Gateway persistence error: failed to record usage log\"}");
            }

            return response;
        } else {
            // Provider returned 4xx/5xx: release hold and record failed status
            budgetService.releaseReservation(keyId, estimate.estimatedCostInr());
            try {
                UsageLog failureLog = new UsageLog(
                        apiKey,
                        estimate.model(),
                        0,
                        0,
                        BigDecimal.ZERO,
                        "primary",
                        "FAILED"
                );
                usageLogRepository.save(failureLog);
            } catch (Exception ignored) {}

            return response;
        }
    }
}
