package com.gateway.auth;

import com.gateway.key.ApiKey;
import com.gateway.key.ApiKeyRepository;
import com.gateway.key.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_KEY_ATTR = "AUTHENTICATED_KEY";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ApiKeyRepository apiKeyRepository) {
        this.apiKeyService = apiKeyService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.startsWith("/actuator")
                || path.startsWith("/admin")
                || (path.equals("/usage") && request.getParameter("key") != null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String xApiKeyHeader = request.getHeader("X-API-Key");

        String bearerKey = null;
        if (authHeader != null && !authHeader.isBlank()) {
            if (authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                bearerKey = authHeader.substring(BEARER_PREFIX.length()).trim();
            } else {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED.value(),
                        "Invalid Authorization header format. Expected 'Bearer <key>'");
                return;
            }
        }

        String xApiKey = (xApiKeyHeader != null && !xApiKeyHeader.isBlank()) ? xApiKeyHeader.trim() : null;

        // Check for conflicting credentials if both headers are supplied
        if (bearerKey != null && xApiKey != null && !bearerKey.equals(xApiKey)) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED.value(),
                    "Conflicting credentials: Authorization and X-API-Key headers do not match");
            return;
        }

        String rawKey = (bearerKey != null) ? bearerKey : xApiKey;
        if (rawKey == null || rawKey.isBlank()) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED.value(),
                    "Missing API key. Provide via 'Authorization: Bearer <key>' or 'X-API-Key' header");
            return;
        }

        // SHA-256 hash lookup
        String keyHash = apiKeyService.hashKey(rawKey);
        Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyHash(keyHash);

        if (apiKeyOpt.isEmpty()) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED.value(), "Invalid API key");
            return;
        }

        ApiKey apiKey = apiKeyOpt.get();
        if (!apiKey.isActive()) {
            writeErrorResponse(response, HttpStatus.FORBIDDEN.value(), "API key has been deactivated");
            return;
        }

        // Stash authenticated key entity for downstream request lifecycle
        request.setAttribute(AUTHENTICATED_KEY_ATTR, apiKey);
        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(String.format("{\"error\":\"%s\"}", message));
    }
}

