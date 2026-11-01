package com.gateway.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.key.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        budgetService = new BudgetService(
                apiKeyRepository,
                new ObjectMapper(),
                new BigDecimal("84.00"),
                512,
                4096
        );
    }

    @Test
    void calculateCostInrShouldMatchFormulaExactly() {
        // gpt-4o-mini: input = $0.15 / 1M, output = $0.60 / 1M
        // 1000 in, 500 out
        // inCost = 0.00015 USD, outCost = 0.00030 USD -> Total = 0.00045 USD
        // 0.00045 * 84.00 = 0.037800 INR
        BigDecimal cost = budgetService.calculateCostInr("gpt-4o-mini", 1000, 500);

        assertEquals(new BigDecimal("0.037800"), cost);
    }

    @Test
    void estimateCostShouldExtractMessageLengthAndApplyCeiling() {
        // 40 characters -> ~10 input tokens
        String requestJson = """
            {
                "model": "gpt-4o-mini",
                "messages": [
                    {"role": "user", "content": "1234567890123456789012345678901234567890"}
                ],
                "max_tokens": 10000
            }
            """;

        BudgetService.CostEstimate estimate = budgetService.estimateCost(requestJson);

        assertEquals("gpt-4o-mini", estimate.model());
        assertEquals(10, estimate.estimatedInputTokens());
        // Requested 10,000 max_tokens capped at ceiling 4096
        assertEquals(4096, estimate.estimatedOutputTokens());
        assertTrue(estimate.estimatedCostInr().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void reserveShouldReturnTrueWhenAtomicUpdateSucceeds() {
        UUID keyId = UUID.randomUUID();
        BigDecimal est = new BigDecimal("0.500000");

        when(apiKeyRepository.reserveBudget(keyId, est)).thenReturn(1);

        boolean reserved = budgetService.reserve(keyId, est);

        assertTrue(reserved);
        verify(apiKeyRepository).reserveBudget(keyId, est);
    }

    @Test
    void reserveShouldReturnFalseWhenBudgetExhausted() {
        UUID keyId = UUID.randomUUID();
        BigDecimal est = new BigDecimal("0.500000");

        // 0 rows updated means (spent + reserved + est) > budget_inr
        when(apiKeyRepository.reserveBudget(keyId, est)).thenReturn(0);

        boolean reserved = budgetService.reserve(keyId, est);

        assertFalse(reserved);
        verify(apiKeyRepository).reserveBudget(keyId, est);
    }

    @Test
    void reconcileShouldDelegateToRepository() {
        UUID keyId = UUID.randomUUID();
        BigDecimal est = new BigDecimal("0.500000");
        BigDecimal actual = new BigDecimal("0.250000");

        budgetService.reconcile(keyId, est, actual);

        verify(apiKeyRepository).reconcileUsage(keyId, est, actual);
    }

    @Test
    void releaseReservationShouldDelegateToRepository() {
        UUID keyId = UUID.randomUUID();
        BigDecimal est = new BigDecimal("0.500000");

        budgetService.releaseReservation(keyId, est);

        verify(apiKeyRepository).releaseReservation(keyId, est);
    }
}

