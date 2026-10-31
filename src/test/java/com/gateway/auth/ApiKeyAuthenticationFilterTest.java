package com.gateway.auth;

import com.gateway.key.ApiKey;
import com.gateway.key.ApiKeyRepository;
import com.gateway.key.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiKeyAuthenticationFilterTest.TestProtectedController.class)
@Import({ApiKeyAuthenticationFilter.class, ApiKeyAuthenticationFilterTest.TestConfig.class})
class ApiKeyAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public TestProtectedController testProtectedController() {
            return new TestProtectedController();
        }
    }

    @RestController
    static class TestProtectedController {
        @GetMapping("/v1/test")
        public String test(HttpServletRequest request) {
            ApiKey key = (ApiKey) request.getAttribute(ApiKeyAuthenticationFilter.AUTHENTICATED_KEY_ATTR);
            return "authenticated:" + key.getName();
        }
    }

    private static final String RAW_KEY = "gw_live_1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String HASH_KEY = "hash_12345";

    @BeforeEach
    void setUp() {
        when(apiKeyService.hashKey(RAW_KEY)).thenReturn(HASH_KEY);
    }

    @Test
    void missingApiKeyShouldReturn401() throws Exception {
        mockMvc.perform(get("/v1/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing API key. Provide via 'Authorization: Bearer <key>' or 'X-API-Key' header"));
    }

    @Test
    void malformedAuthorizationHeaderShouldReturn401() throws Exception {
        mockMvc.perform(get("/v1/test").header("Authorization", "Token " + RAW_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid Authorization header format. Expected 'Bearer <key>'"));
    }

    @Test
    void conflictingCredentialsShouldReturn401() throws Exception {
        mockMvc.perform(get("/v1/test")
                        .header("Authorization", "Bearer " + RAW_KEY)
                        .header("X-API-Key", "gw_live_different_key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Conflicting credentials: Authorization and X-API-Key headers do not match"));
    }

    @Test
    void unknownKeyHashShouldReturn401() throws Exception {
        when(apiKeyRepository.findByKeyHash(HASH_KEY)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/test").header("Authorization", "Bearer " + RAW_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid API key"));
    }

    @Test
    void deactivatedKeyShouldReturn403() throws Exception {
        ApiKey deactivated = new ApiKey(HASH_KEY, "test-key", new BigDecimal("100.00"));
        deactivated.setActive(false);

        when(apiKeyRepository.findByKeyHash(HASH_KEY)).thenReturn(Optional.of(deactivated));

        mockMvc.perform(get("/v1/test").header("Authorization", "Bearer " + RAW_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("API key has been deactivated"));
    }

    @Test
    void validBearerKeyShouldAuthenticateAndPassDownstream() throws Exception {
        ApiKey active = new ApiKey(HASH_KEY, "test-client", new BigDecimal("500.00"));
        when(apiKeyRepository.findByKeyHash(HASH_KEY)).thenReturn(Optional.of(active));

        mockMvc.perform(get("/v1/test").header("Authorization", "Bearer " + RAW_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("authenticated:test-client"));
    }

    @Test
    void validXApiKeyHeaderShouldAuthenticateAndPassDownstream() throws Exception {
        ApiKey active = new ApiKey(HASH_KEY, "test-client", new BigDecimal("500.00"));
        when(apiKeyRepository.findByKeyHash(HASH_KEY)).thenReturn(Optional.of(active));

        mockMvc.perform(get("/v1/test").header("X-API-Key", RAW_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("authenticated:test-client"));
    }

    @Test
    void matchingBothHeadersShouldAuthenticateSuccessfully() throws Exception {
        ApiKey active = new ApiKey(HASH_KEY, "test-client", new BigDecimal("500.00"));
        when(apiKeyRepository.findByKeyHash(HASH_KEY)).thenReturn(Optional.of(active));

        mockMvc.perform(get("/v1/test")
                        .header("Authorization", "Bearer " + RAW_KEY)
                        .header("X-API-Key", RAW_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("authenticated:test-client"));
    }
}

