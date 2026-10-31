package com.gateway.key;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository);
    }

    @Test
    void generateRawKeyShouldHavePrefixAndCorrectEntropy() {
        String key = apiKeyService.generateRawKey();
        assertTrue(key.startsWith("gw_live_"), "Key must start with prefix gw_live_");
        assertEquals(8 + 64, key.length(), "Key must contain prefix (8 chars) + 64 hex characters (32 bytes)");
    }

    @Test
    void generatedKeysShouldBeUnique() {
        String key1 = apiKeyService.generateRawKey();
        String key2 = apiKeyService.generateRawKey();
        assertNotEquals(key1, key2, "Subsequent keys must be distinct");
    }

    @Test
    void hashKeyShouldBeDeterministicAnd64HexChars() {
        String rawKey = "gw_live_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String hash1 = apiKeyService.hashKey(rawKey);
        String hash2 = apiKeyService.hashKey(rawKey);

        assertEquals(hash1, hash2, "Hashing identical input must produce identical hash");
        assertEquals(64, hash1.length(), "SHA-256 hash in hex must be exactly 64 characters");
    }

    @Test
    void createKeyShouldPersistOnlyHashAndReturnRawKeyOnce() {
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BigDecimal budget = new BigDecimal("1000.000000");
        ApiKeyService.KeyCreationResult result = apiKeyService.createKey("prod-service", budget);

        assertNotNull(result.rawKey());
        assertTrue(result.rawKey().startsWith("gw_live_"));
        assertEquals("prod-service", result.name());
        assertEquals(budget, result.budgetInr());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());

        ApiKey persisted = captor.getValue();
        assertNotEquals(result.rawKey(), persisted.getKeyHash(), "Raw key must never be persisted");
        assertEquals(apiKeyService.hashKey(result.rawKey()), persisted.getKeyHash(), "Persisted hash must match SHA-256 of raw key");
    }

    @Test
    void constantTimeEqualsShouldValidateCorrectly() {
        assertTrue(apiKeyService.constantTimeEquals("secret123", "secret123"));
        assertFalse(apiKeyService.constantTimeEquals("secret123", "secret124"));
        assertFalse(apiKeyService.constantTimeEquals("secret123", null));
        assertFalse(apiKeyService.constantTimeEquals(null, "secret123"));
    }
}

