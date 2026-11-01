package com.gateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmProviderServiceTest {

    private MockRestServiceServer mockPrimaryServer;
    private MockRestServiceServer mockFallbackServer;
    private LlmProviderService llmProviderService;

    @BeforeEach
    void setUp() {
        RestClient.Builder primaryBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1/chat/completions");
        RestClient.Builder fallbackBuilder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");

        mockPrimaryServer = MockRestServiceServer.bindTo(primaryBuilder).build();
        mockFallbackServer = MockRestServiceServer.bindTo(fallbackBuilder).build();

        llmProviderService = new LlmProviderService(
                primaryBuilder.build(),
                fallbackBuilder.build(),
                "gemini-1.5-flash",
                true,
                new ObjectMapper()
        );
    }

    @Test
    void shouldRouteGroqModelToPrimaryProvider() {
        String requestJson = "{\"model\":\"llama-3.1-8b-instant\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String responseJson = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"hello from groq\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";

        mockPrimaryServer.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = llmProviderService.forwardChatCompletion(requestJson);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("groq", response.getHeaders().getFirst(LlmProviderService.PROVIDER_USED_HEADER));
        assertEquals(responseJson, response.getBody());
        mockPrimaryServer.verify();
    }

    @Test
    void shouldRouteGeminiModelDirectlyToFallbackProvider() {
        String requestJson = "{\"model\":\"gemini-1.5-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String responseJson = "{\"id\":\"chatcmpl-2\",\"choices\":[{\"message\":{\"content\":\"hello from gemini\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";

        mockFallbackServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = llmProviderService.forwardChatCompletion(requestJson);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("gemini", response.getHeaders().getFirst(LlmProviderService.PROVIDER_USED_HEADER));
        assertEquals(responseJson, response.getBody());
        mockFallbackServer.verify();
    }

    @Test
    void shouldFailoverToGeminiWhenPrimaryTimesOut() {
        String requestJson = "{\"model\":\"llama-3.1-8b-instant\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String fallbackResponseJson = "{\"id\":\"chatcmpl-3\",\"choices\":[{\"message\":{\"content\":\"hello from gemini fallback\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";

        // Primary times out
        mockPrimaryServer.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new java.net.SocketTimeoutException("Primary timeout")));

        // Fallback succeeds with adapted model
        mockFallbackServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fallbackResponseJson, MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = llmProviderService.forwardChatCompletion(requestJson);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("gemini-fallback", response.getHeaders().getFirst(LlmProviderService.PROVIDER_USED_HEADER));
        assertEquals(fallbackResponseJson, response.getBody());
        mockPrimaryServer.verify();
        mockFallbackServer.verify();
    }
}
