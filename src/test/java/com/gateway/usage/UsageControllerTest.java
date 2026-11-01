package com.gateway.usage;

import com.gateway.auth.ApiKeyAuthenticationFilter;
import com.gateway.key.ApiKey;
import com.gateway.key.ApiKeyRepository;
import com.gateway.key.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UsageController.class)
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private UsageLogRepository usageLogRepository;

    @Test
    void shouldReturn404WhenKeyParamNotFound() throws Exception {
        when(apiKeyService.hashKey("gw_live_missing")).thenReturn("hash_missing");
        when(apiKeyRepository.findByKeyHash("hash_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/usage").param("key", "gw_live_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("API key not found"));
    }

    @Test
    void shouldReturn401WhenNoKeyParamAndNoAuthAttribute() throws Exception {
        mockMvc.perform(get("/usage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing API key. Provide via ?key=... or Authorization header"));
    }

    @Test
    void shouldReturnUsageSummaryWhenKeyParamProvided() throws Exception {
        UUID keyId = UUID.randomUUID();
        ApiKey key = new ApiKey("hash_abc", "reporting-app", new BigDecimal("100.000000"));

        when(apiKeyService.hashKey("gw_live_valid")).thenReturn("hash_abc");
        when(apiKeyRepository.findByKeyHash("hash_abc")).thenReturn(Optional.of(key));
        when(usageLogRepository.countByApiKeyId(any())).thenReturn(1L);

        UsageLog sampleLog = new UsageLog(
                key, "gpt-4o-mini", 50, 25, new BigDecimal("0.020000"), "primary", "SUCCESS"
        );
        when(usageLogRepository.findByApiKeyIdOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(List.of(sampleLog));

        mockMvc.perform(get("/usage").param("key", "gw_live_valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("reporting-app"))
                .andExpect(jsonPath("$.budgetInr").value(100.000000))
                .andExpect(jsonPath("$.spentInr").value(0.000000))
                .andExpect(jsonPath("$.remainingBudgetInr").value(100.000000))
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.recentLogs[0].model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.recentLogs[0].costInr").value(0.020000))
                .andExpect(jsonPath("$.recentLogs[0].status").value("SUCCESS"));
    }

    @Test
    void shouldReturnUsageSummaryWhenAuthenticatedViaRequestAttribute() throws Exception {
        UUID keyId = UUID.randomUUID();
        ApiKey key = new ApiKey("hash_xyz", "mobile-app", new BigDecimal("50.000000"));

        when(apiKeyRepository.findById(any())).thenReturn(Optional.of(key));
        when(usageLogRepository.countByApiKeyId(any())).thenReturn(0L);
        when(usageLogRepository.findByApiKeyIdOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/usage")
                        .requestAttr(ApiKeyAuthenticationFilter.AUTHENTICATED_KEY_ATTR, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("mobile-app"))
                .andExpect(jsonPath("$.budgetInr").value(50.000000))
                .andExpect(jsonPath("$.remainingBudgetInr").value(50.000000))
                .andExpect(jsonPath("$.totalRequests").value(0));
    }
}

