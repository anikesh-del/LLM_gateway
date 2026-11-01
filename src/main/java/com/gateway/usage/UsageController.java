package com.gateway.usage;

import com.gateway.auth.ApiKeyAuthenticationFilter;
import com.gateway.key.ApiKey;
import com.gateway.key.ApiKeyRepository;
import com.gateway.key.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class UsageController {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyService apiKeyService;
    private final UsageLogRepository usageLogRepository;

    public UsageController(
            ApiKeyRepository apiKeyRepository,
            ApiKeyService apiKeyService,
            UsageLogRepository usageLogRepository
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyService = apiKeyService;
        this.usageLogRepository = usageLogRepository;
    }

    public record LogEntry(
            UUID id,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal costInr,
            String provider,
            String status,
            Instant timestamp
    ) {}

    public record UsageSummaryResponse(
            UUID keyId,
            String name,
            BigDecimal budgetInr,
            BigDecimal spentInr,
            BigDecimal reservedInr,
            BigDecimal remainingBudgetInr,
            long totalRequests,
            List<LogEntry> recentLogs
    ) {}

    @GetMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getUsage(
            @RequestParam(value = "key", required = false) String rawKeyParam,
            HttpServletRequest request
    ) {
        ApiKey apiKey = null;

        if (rawKeyParam != null && !rawKeyParam.isBlank()) {
            String hash = apiKeyService.hashKey(rawKeyParam.trim());
            apiKey = apiKeyRepository.findByKeyHash(hash).orElse(null);
            if (apiKey == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "API key not found"));
            }
        } else {
            apiKey = (ApiKey) request.getAttribute(ApiKeyAuthenticationFilter.AUTHENTICATED_KEY_ATTR);
            if (apiKey == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Missing API key. Provide via ?key=... or Authorization header"));
            }
            // Fetch fresh state from DB to get the latest settled numbers
            apiKey = apiKeyRepository.findById(apiKey.getId()).orElse(apiKey);
        }

        BigDecimal totalCommitted = apiKey.getSpentInr().add(apiKey.getReservedInr());
        BigDecimal remaining = apiKey.getBudgetInr().subtract(totalCommitted);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO;
        }

        long totalRequests = usageLogRepository.countByApiKeyId(apiKey.getId());
        List<UsageLog> recentList = usageLogRepository.findByApiKeyIdOrderByCreatedAtDesc(
                apiKey.getId(),
                PageRequest.of(0, 20)
        );

        List<LogEntry> logEntries = recentList.stream()
                .map(log -> new LogEntry(
                        log.getId(),
                        log.getModel(),
                        log.getInputTokens(),
                        log.getOutputTokens(),
                        log.getCostInr(),
                        log.getProvider(),
                        log.getStatus(),
                        log.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok(new UsageSummaryResponse(
                apiKey.getId(),
                apiKey.getName(),
                apiKey.getBudgetInr(),
                apiKey.getSpentInr(),
                apiKey.getReservedInr(),
                remaining,
                totalRequests,
                logEntries
        ));
    }
}

