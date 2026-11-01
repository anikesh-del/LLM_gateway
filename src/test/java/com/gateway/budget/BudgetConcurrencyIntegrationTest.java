package com.gateway.budget;

import com.gateway.auth.ApiKeyAuthenticationFilter;
import com.gateway.key.ApiKey;
import com.gateway.key.ApiKeyRepository;
import com.gateway.key.ApiKeyService;
import com.gateway.provider.LlmProviderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class BudgetConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private com.gateway.usage.UsageLogRepository usageLogRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private BudgetService budgetService;

    @MockBean
    private LlmProviderService llmProviderService;

    private UUID testKeyId;
    private String rawKey;

    @BeforeEach
    void setUp() {
        // Mock upstream provider to return a successful chat response with token usage
        String upstreamResponse = """
            {
                "id": "chatcmpl-test",
                "choices": [{"message": {"role": "assistant", "content": "Done"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
            }
            """;
        when(llmProviderService.forwardChatCompletion(anyString()))
                .thenReturn(ResponseEntity.ok(upstreamResponse));

        // Create key with budget sufficient for EXACTLY 1 request
        String samplePayload = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":100}";
        BigDecimal estimatedCost = budgetService.estimateCost(samplePayload).estimatedCostInr();

        // Budget set to exactly 1 * estimatedCost
        var creationResult = apiKeyService.createKey("concurrency-test-key", estimatedCost);
        testKeyId = creationResult.id();
        rawKey = creationResult.rawKey();
    }

    @AfterEach
    void tearDown() {
        if (testKeyId != null) {
            usageLogRepository.deleteAll(usageLogRepository.findByApiKeyIdOrderByCreatedAtDesc(testKeyId, org.springframework.data.domain.Pageable.unpaged()));
            apiKeyRepository.deleteById(testKeyId);
        }
    }

    @Test
    void tenConcurrentRequestsOnOneRequestBudgetShouldResultInExactlyOne200AndNine402s() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger status200Count = new AtomicInteger(0);
        AtomicInteger status402Count = new AtomicInteger(0);
        AtomicInteger otherStatusCount = new AtomicInteger(0);

        String payload = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":100}";

        Callable<Void> task = () -> {
            readyLatch.countDown();
            try {
                // Wait for all threads to be ready so they fire simultaneously
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + rawKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();

                int status = result.getResponse().getStatus();
                if (status == 200) {
                    status200Count.incrementAndGet();
                } else if (status == 402) {
                    status402Count.incrementAndGet();
                } else {
                    otherStatusCount.incrementAndGet();
                }
            } catch (Exception e) {
                otherStatusCount.incrementAndGet();
            }
            return null;
        };

        for (int i = 0; i < threadCount; i++) {
            executor.submit(task);
        }

        // Wait until all threads are armed and waiting at startLatch
        readyLatch.await(5, TimeUnit.SECONDS);
        // Release the latch: all 10 threads hit the endpoint at the exact same moment
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Assert HTTP status distribution
        assertEquals(0, otherStatusCount.get(), "No unexpected status codes or crashes");
        assertEquals(1, status200Count.get(), "Exactly 1 request must succeed with 200 OK");
        assertEquals(9, status402Count.get(), "Exactly 9 requests must be blocked with 402 Payment Required");

        // Verify database state: no lingering reservations
        ApiKey finalKey = apiKeyRepository.findById(testKeyId).orElseThrow();
        assertEquals(0, finalKey.getReservedInr().compareTo(BigDecimal.ZERO), "In-flight reservations must be 0 after completion");
        assertEquals(1, finalKey.getSpentInr().compareTo(BigDecimal.ZERO), "Spent must be greater than 0 from the single successful request");
    }
}

