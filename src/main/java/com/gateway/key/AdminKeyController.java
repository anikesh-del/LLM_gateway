package com.gateway.key;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminKeyController {

    private final ApiKeyService apiKeyService;
    private final String adminApiKey;

    public AdminKeyController(
            ApiKeyService apiKeyService,
            @Value("${ADMIN_API_KEY:}") String adminApiKey
    ) {
        this.apiKeyService = apiKeyService;
        this.adminApiKey = adminApiKey;
    }

    public record CreateKeyRequest(
            String name,
            BigDecimal budgetInr
    ) {}

    public record CreateKeyResponse(
            UUID id,
            String name,
            BigDecimal budgetInr,
            String key
    ) {}

    @PostMapping("/keys")
    public ResponseEntity<?> createKey(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedAdminKey,
            @RequestBody(required = false) CreateKeyRequest request
    ) {
        // Fail-closed authentication check
        if (adminApiKey == null || adminApiKey.isBlank() || !apiKeyService.constantTimeEquals(adminApiKey, providedAdminKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: invalid or missing X-Admin-Key"));
        }

        // Input validation
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "name must not be blank"));
        }

        if (request.budgetInr() == null || request.budgetInr().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "budgetInr must be a positive number greater than zero"));
        }

        ApiKeyService.KeyCreationResult result = apiKeyService.createKey(
                request.name().trim(),
                request.budgetInr()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateKeyResponse(
                        result.id(),
                        result.name(),
                        result.budgetInr(),
                        result.rawKey()
                ));
    }
}

