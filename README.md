# Minimal LLM Gateway

A lightweight, production-grade HTTP LLM Gateway developed in Java 21 and Spring Boot 3.3.5 that sits between callers and upstream LLM providers, adding **virtual API keys**, **pre-call atomic budget enforcement in INR ($\text{₹}$)**, **synchronous audit logging**, and **multi-provider resilience** with automatic fallback from Groq to Google Gemini.

- **Live Deployed Gateway**: [https://llm-gateway-2v9l.onrender.com](https://llm-gateway-2v9l.onrender.com)
- **Primary Provider**: Groq Cloud (`openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`)
- **Fallback Provider**: Google Gemini (`gemini-3.5-flash-lite`)
- **Architecture & Trade-Offs**: See [`DECISIONS.md`](DECISIONS.md)
- **AI Collaboration Log**: See [`AI-LOG.md`](AI-LOG.md)

---

## Quickstart: Working cURL Examples (Live URL)

### 1. Create a Virtual API Key (Admin Protected)

Generate a new virtual API key with an assigned budget in INR ($\text{₹}$):

```bash
curl -X POST "https://llm-gateway-2v9l.onrender.com/admin/keys" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: Yb+xTJ6GDXKRQOmLd/XWHpmp32TIwKJDTfivCnVIIvk=" \
  -d '{"name": "production-tester", "budgetInr": 50.00}'
```

**Response (HTTP 201 Created)**:
```json
{
  "id": "c00f1cfd-a381-4e07-9a76-cabf55e1fd14",
  "name": "production-tester",
  "budgetInr": 50.00,
  "key": "gw_live_8a59d93264a1578305d056d3d04998fd9a0eea73b91a73903ca11227c668e016"
}
```
*(The raw key is returned once; only its SHA-256 hash is stored in PostgreSQL).*

---

### 2. Send Chat Completions (`POST /v1/chat/completions`)

#### Route A: Primary Provider (Groq)
```bash
curl -i -X POST "https://llm-gateway-2v9l.onrender.com/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer gw_live_8a59d93264a1578305d056d3d04998fd9a0eea73b91a73903ca11227c668e016" \
  -d '{
    "model": "openai/gpt-oss-20b",
    "messages": [{"role": "user", "content": "Respond in 3 words: Gateway is running!"}]
  }'
```

**Response (HTTP 200 OK)**:
```http
HTTP/1.1 200 OK
x-provider-used: groq
Content-Type: application/json

{
  "id": "chatcmpl-120e0a75-6c60-4648-9d31-cd73cbef4e9d",
  "model": "openai/gpt-oss-20b",
  "choices": [
    { "index": 0, "message": { "role": "assistant", "content": "GroqLive!" }, "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 76, "completion_tokens": 102, "total_tokens": 178 }
}
```

#### Route B: Fallback Provider (Gemini)
```bash
curl -i -X POST "https://llm-gateway-2v9l.onrender.com/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer gw_live_8a59d93264a1578305d056d3d04998fd9a0eea73b91a73903ca11227c668e016" \
  -d '{
    "model": "gemini-3.5-flash-lite",
    "messages": [{"role": "user", "content": "Respond in 3 words: Gemini is running!"}]
  }'
```

**Response (HTTP 200 OK)**:
```http
HTTP/1.1 200 OK
x-provider-used: gemini
Content-Type: application/json

{
  "id": "X_vmarieIt-VmtkP7vq60A8",
  "model": "gemini-3.5-flash-lite",
  "choices": [
    { "index": 0, "message": { "role": "assistant", "content": "GeminiLive!" }, "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9 }
}
```

---

### 3. Query Spend & Audit Logs (`GET /usage?key=...`)

Query current expenditure, remaining balance, and historical requests without needing an authorization header:

```bash
curl "https://llm-gateway-2v9l.onrender.com/usage?key=gw_live_8a59d93264a1578305d056d3d04998fd9a0eea73b91a73903ca11227c668e016"
```

**Response (HTTP 200 OK)**:
```json
{
  "keyId": "c00f1cfd-a381-4e07-9a76-cabf55e1fd14",
  "name": "production-tester",
  "budgetInr": 50.000000,
  "spentInr": 0.053097,
  "reservedInr": 0.000000,
  "remainingBudgetInr": 49.946903,
  "totalRequests": 22,
  "recentLogs": [
    {
      "id": "62ec1950-3f7a-4bd8-81f7-bc8f9b1a6d1c",
      "model": "gemini-3.5-flash-lite",
      "inputTokens": 11,
      "outputTokens": 4,
      "costInr": 0.000966,
      "provider": "gemini",
      "status": "SUCCESS",
      "timestamp": "2026-11-01T08:40:05.749587Z"
    },
    {
      "id": "04728849-785d-4910-af3b-f2ef8722f7b8",
      "model": "openai/gpt-oss-20b",
      "inputTokens": 74,
      "outputTokens": 37,
      "costInr": 0.001243,
      "provider": "groq",
      "status": "SUCCESS",
      "timestamp": "2026-11-01T08:28:54.964080Z"
    }
  ]
}
```

---

## Core Architecture & Engineering Highlights

```
                       Inbound Request
                              │
               [ApiKeyAuthenticationFilter]
                              │
               [Budget Pre-Reservation (SQL)]
             (Atomic check: spent + est <= cap)
                              │
                    ┌─────────┴─────────┐
             Under Budget          Over Budget
                    │                   │
            [Dispatch Provider]   HTTP 402 Payment Required
                    │             (Zero provider egress!)
            ┌───────┴───────┐
       Primary (Groq)  Fallback (Gemini)
            │               │
     Reconcile Tokens & Settled Cost
            │
    [Synchronous Audit Log (PostgreSQL)]
```

1. **Atomic Concurrency Protection**: Eliminates read-then-write overspend races using a single atomic conditional native query:
   ```sql
   UPDATE api_keys 
   SET reserved_inr = reserved_inr + :estimatedCost, updated_at = NOW()
   WHERE id = :id AND is_active = TRUE AND (spent_inr + reserved_inr + :estimatedCost) <= budget_inr;
   ```
2. **Fixed-Point Financial Accuracy**: Database stores balances in `NUMERIC(14, 6)` and Java uses `BigDecimal` with half-up rounding, eliminating IEEE 754 floating-point drift.
3. **Resilience & Fail-Safe Release**: Automatically fails over to Google Gemini upon primary socket timeouts or 5xx server errors. On failed requests, reservations are reset to 0 with zero billing.

---

## Local Development & Setup

### Prerequisites
- Java 21 (JDK)
- Docker & Docker Compose

### 1. Clone & Configure Environment
```bash
git clone https://github.com/anikesh-del/LLM_gateway.git
cd LLM_gateway
cp .env.example .env
```

Edit `.env` with your credentials:
```ini
ADMIN_API_KEY=your-admin-secret
PRIMARY_PROVIDER_API_KEY=gsk_your_groq_api_key
PRIMARY_PROVIDER_BASE_URL=https://api.groq.com/openai/v1
PRIMARY_MODEL=openai/gpt-oss-20b
FALLBACK_PROVIDER_API_KEY=your_gemini_api_key
FALLBACK_PROVIDER_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
FALLBACK_MODEL=gemini-3.5-flash-lite
USD_INR=84.00
```

### 2. Start PostgreSQL Container
```bash
docker compose up -d
```

### 3. Run the Gateway
```bash
./mvnw spring-boot:run
```
The gateway will start on port `8080` with Flyway automatically running migrations.

### 4. Run Automated Test Suite
```bash
./mvnw test
```
The test suite verifies:
- Virtual key entropy and SHA-256 deterministic hashing (`ApiKeyServiceTest`).
- Authentication filter and header conflict handling (`ApiKeyAuthenticationFilterTest`).
- Admin endpoint access controls and input validation (`AdminKeyControllerTest`).
- Upstream provider timeouts and fallback routing (`LlmProviderServiceTest`).
- Spend inquiry calculations and log pagination (`UsageControllerTest`).
- 10-thread parallel budget race condition enforcement (`BudgetConcurrencyIntegrationTest`).

---

## License
MIT License.
