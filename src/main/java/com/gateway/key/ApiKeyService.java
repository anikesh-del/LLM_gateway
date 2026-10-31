package com.gateway.key;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "gw_live_";
    private static final int KEY_BYTE_LENGTH = 32; // 256 bits of entropy
    private final SecureRandom secureRandom = new SecureRandom();
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    public record KeyCreationResult(
            UUID id,
            String name,
            BigDecimal budgetInr,
            String rawKey
    ) {}

    @Transactional
    public KeyCreationResult createKey(String name, BigDecimal budgetInr) {
        String rawKey = generateRawKey();
        String keyHash = hashKey(rawKey);

        ApiKey apiKey = new ApiKey(keyHash, name, budgetInr);
        ApiKey saved = apiKeyRepository.save(apiKey);

        // Return rawKey once; it will not be persisted or recoverable later
        return new KeyCreationResult(saved.getId(), saved.getName(), saved.getBudgetInr(), rawKey);
    }

    public String generateRawKey() {
        byte[] randomBytes = new byte[KEY_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        return KEY_PREFIX + HexFormat.of().formatHex(randomBytes);
    }

    public String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}

