package com.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

@Service
public class LlmProviderService {

    public static final String PROVIDER_USED_HEADER = "X-Provider-Used";

    private final RestClient primaryClient;
    private final RestClient fallbackClient;
    private final String fallbackModel;
    private final boolean fallbackConfigured;
    private final ObjectMapper objectMapper;

    @Autowired 
    public LlmProviderService(
            @Value("${gateway.providers.primary.base-url:https://api.groq.com/openai/v1}") String primaryBaseUrl,
            @Value("${gateway.providers.primary.api-key:}") String primaryApiKey,
            @Value("${gateway.providers.fallback.base-url:https://generativelanguage.googleapis.com/v1beta/openai}") String fallbackBaseUrl,
            @Value("${gateway.providers.fallback.api-key:}") String fallbackApiKey,
            @Value("${gateway.providers.fallback.model:gemini-1.5-flash}") String fallbackModel,
            @Value("${PRIMARY_PROVIDER_CONNECT_TIMEOUT_MS:5000}") int connectTimeoutMs,
            @Value("${PRIMARY_PROVIDER_READ_TIMEOUT_MS:30000}") int readTimeoutMs,
            ObjectMapper objectMapper
    ) {
        this(
                buildRestClient(primaryBaseUrl, primaryApiKey, connectTimeoutMs, readTimeoutMs, RestClient.builder()),
                buildRestClient(fallbackBaseUrl, fallbackApiKey, connectTimeoutMs, readTimeoutMs, RestClient.builder()),
                fallbackModel,
                fallbackApiKey != null && !fallbackApiKey.isBlank(),
                objectMapper
        );
    }

    public LlmProviderService(
            RestClient primaryClient,
            RestClient fallbackClient,
            String fallbackModel,
            boolean fallbackConfigured,
            ObjectMapper objectMapper
    ) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.fallbackModel = fallbackModel;
        this.fallbackConfigured = fallbackConfigured;
        this.objectMapper = objectMapper;
    }

    private static RestClient buildRestClient(
            String baseUrl,
            String apiKey,
            int connectTimeoutMs,
            int readTimeoutMs,
            RestClient.Builder builder
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        return builder
                .baseUrl(normalized)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ResponseEntity<String> forwardChatCompletion(String requestBody) {
        String requestedModel = extractModel(requestBody);
        boolean isExplicitGemini = requestedModel.toLowerCase().contains("gemini");

        // 1. If caller explicitly requested Gemini, route directly to Gemini
        if (isExplicitGemini && fallbackConfigured) {
            return dispatch(fallbackClient, requestBody, "gemini");
        }

        // 2. Otherwise route to primary provider (Groq)
        try {
            ResponseEntity<String> response = dispatch(primaryClient, requestBody, "groq");
            // If primary returned a 5xx server error, attempt fallback if available
            if (response.getStatusCode().is5xxServerError() && fallbackConfigured) {
                return attemptFallback(requestBody, "Primary 5xx server error");
            }
            return response;
        } catch (ResourceAccessException e) {
            // Primary socket read or connect timeout: attempt fallback
            if (fallbackConfigured) {
                return attemptFallback(requestBody, "Primary timeout");
            }
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PROVIDER_USED_HEADER, "groq")
                    .body("{\"error\":\"Primary provider timeout after configured threshold\"}");
        }
    }

    private ResponseEntity<String> attemptFallback(String originalRequestBody, String triggerReason) {
        try {
            // Adapt model in request body to fallback model if original model is not supported by Gemini
            String adaptedBody = adaptModelForFallback(originalRequestBody);
            ResponseEntity<String> fallbackResponse = dispatch(fallbackClient, adaptedBody, "gemini-fallback");
            return ResponseEntity.status(fallbackResponse.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PROVIDER_USED_HEADER, "gemini-fallback")
                    .body(fallbackResponse.getBody());
        } catch (Exception fallbackError) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\":\"Upstream failure: %s, and fallback provider failed\"}", triggerReason));
        }
    }

    private ResponseEntity<String> dispatch(RestClient client, String body, String providerLabel) {
        try {
            ResponseEntity<String> resp = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            return ResponseEntity.status(resp.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PROVIDER_USED_HEADER, providerLabel)
                    .body(resp.getBody());
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PROVIDER_USED_HEADER, providerLabel)
                    .body(e.getResponseBodyAsString());
        }
    }

    private String extractModel(String requestBody) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if (root.hasNonNull("model")) {
                return root.get("model").asText();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String adaptModelForFallback(String requestBody) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if (root.isObject()) {
                ((ObjectNode) root).put("model", fallbackModel);
                return objectMapper.writeValueAsString(root);
            }
        } catch (Exception ignored) {}
        return requestBody;
    }
}
