package com.gateway.key;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminKeyController.class)
@TestPropertySource(properties = "ADMIN_API_KEY=test-admin-secret-12345")
class AdminKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void missingAdminKeyHeaderShouldReturn401() throws Exception {
        var request = new AdminKeyController.CreateKeyRequest("test", new BigDecimal("100.00"));

        mockMvc.perform(post("/admin/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized: invalid or missing X-Admin-Key"));
    }

    @Test
    void wrongAdminKeyHeaderShouldReturn401() throws Exception {
        when(apiKeyService.constantTimeEquals("test-admin-secret-12345", "wrong-key")).thenReturn(false);

        var request = new AdminKeyController.CreateKeyRequest("test", new BigDecimal("100.00"));

        mockMvc.perform(post("/admin/keys")
                        .header("X-Admin-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized: invalid or missing X-Admin-Key"));
    }

    @Test
    void blankNameShouldReturn400() throws Exception {
        when(apiKeyService.constantTimeEquals("test-admin-secret-12345", "test-admin-secret-12345")).thenReturn(true);

        var request = new AdminKeyController.CreateKeyRequest("   ", new BigDecimal("100.00"));

        mockMvc.perform(post("/admin/keys")
                        .header("X-Admin-Key", "test-admin-secret-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("name must not be blank"));
    }

    @Test
    void nonPositiveBudgetShouldReturn400() throws Exception {
        when(apiKeyService.constantTimeEquals("test-admin-secret-12345", "test-admin-secret-12345")).thenReturn(true);

        var request = new AdminKeyController.CreateKeyRequest("test", new BigDecimal("-10.00"));

        mockMvc.perform(post("/admin/keys")
                        .header("X-Admin-Key", "test-admin-secret-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("budgetInr must be a positive number greater than zero"));
    }

    @Test
    void validRequestShouldReturn201WithRawKey() throws Exception {
        when(apiKeyService.constantTimeEquals("test-admin-secret-12345", "test-admin-secret-12345")).thenReturn(true);

        UUID generatedId = UUID.randomUUID();
        when(apiKeyService.createKey(eq("analytics-service"), eq(new BigDecimal("500.00"))))
                .thenReturn(new ApiKeyService.KeyCreationResult(
                        generatedId,
                        "analytics-service",
                        new BigDecimal("500.00"),
                        "gw_live_abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
                ));

        var request = new AdminKeyController.CreateKeyRequest("analytics-service", new BigDecimal("500.00"));

        mockMvc.perform(post("/admin/keys")
                        .header("X-Admin-Key", "test-admin-secret-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(generatedId.toString()))
                .andExpect(jsonPath("$.name").value("analytics-service"))
                .andExpect(jsonPath("$.budgetInr").value(500.00))
                .andExpect(jsonPath("$.key").value("gw_live_abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"));
    }
}

