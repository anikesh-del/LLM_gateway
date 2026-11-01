# AI Collaboration Log (Draft)

Working log documenting AI interactions, prompts, corrections, overrides, and engineering decisions.

---

## Step 1: Project Skeleton & Initial Architecture

### What the AI generated:
- Initial project structure: `.gitignore`, `.env.example`, `docker-compose.yml`, `pom.xml`, `application.yml`, `GatewayApplication.java`, `HealthController.java`, and `HealthControllerTest.java`.
- Maven Wrapper integration: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (targeting Maven 3.9.9).
- Draft decision and collaboration tracking files under `docs/`.

### What the AI suggested vs what I chose / overrode:
- **Budget Unit**: AI presented requests vs tokens vs currency options; I decided on currency in INR with the formula $\text{Total Cost} = \frac{I \times P_i}{1{,}000{,}000} + \frac{O \times P_o}{1{,}000{,}000}$.
- **Build Tool**: Selected Maven over Gradle.
- **Maven Wrapper**: Opted for Maven Wrapper (`mvnw` / `mvnw.cmd`) instead of requiring a global Maven installation.
- **Environment Loading**: Added `spring.config.import: "optional:file:.env[.properties]"` in `application.yml` to import `.env` declarations into Spring Boot's environment.
- **Scope Split**: AI proposed splitting Step 1 into 1a and 1b to honor the 3-file limit; I instructed it to proceed with Option 2 to establish the complete skeleton and `.gitignore` at once.

### Verification & Control:
- Confirmed `.gitignore` was created and active before staging, ensuring `.env` is never tracked by Git.
- Verified all credentials are parameterized with environment variables.


---

## Step 2: Database Schema & Migration

### What the AI generated:
- `src/main/resources/db/migration/V1__create_api_keys_and_usage_logs.sql`.

### What the AI suggested vs what I chose / overrode:
- **Budget Tracking**: Selected Option A (dual tracking with `spent_inr` and `reserved_inr`) over Option B (single column) to maintain clean separation between confirmed usage and in-flight reservations.
- **Primary Keys**: Selected Option A (`UUID`) over Option B (`BIGSERIAL`) to prevent sequential key enumeration.
- **Indexing**: Instructed the AI to add an explicit unique index on `key_hash` to guarantee $O(\log N)$ point lookups on incoming proxy requests.

### Verification & Control:
- Inspected SQL constraints: added `CHECK` constraints on budget, spent, and token values to prevent negative balances at the database level.
- Verified composite index on `(api_key_id, created_at DESC)` for usage log queries.

---

## Step 3a: Virtual Key Generation & Hashing Service

### What the AI generated:
- `src/main/java/com/gateway/key/ApiKey.java` (JPA Entity).
- `src/main/java/com/gateway/key/ApiKeyRepository.java` (Spring Data Repository).
- `src/main/java/com/gateway/key/ApiKeyService.java` (SecureRandom generation, SHA-256 digest, constant-time comparison).
- `src/test/java/com/gateway/key/ApiKeyServiceTest.java` (Unit tests for entropy, prefix, deterministic hashing, and mock persistence).

### What the AI suggested vs what I chose / overrode:
- **Scope Split**: Accepted the split into 3a (core model & hashing) and 3b (admin controller & endpoint protection).
- **Key Prefix**: Instructed use of `gw_live_` prefix with 32 bytes (256 bits) of SecureRandom entropy.
- **Timing Protection**: Instructed use of `MessageDigest.isEqual` for constant-time comparison.

### Verification & Control:
- Inspected `ApiKeyService.createKey`: confirmed raw key is returned in DTO once and never persisted to DB.
- Inspected `ApiKey` entity: confirmed `key_hash` is unique and non-nullable.

---

## Step 3b: Admin Key Issuance Controller & Endpoint Protection

### What the AI generated:
- `src/main/java/com/gateway/key/AdminKeyController.java` (`POST /admin/keys`).
- `src/test/java/com/gateway/key/AdminKeyControllerTest.java` (WebMvcTests covering 401 unauthorized, 400 validation, and 201 created).

### What the AI suggested vs what I chose / overrode:
- **Authentication**: Chose Option 1 (controller guard with fail-closed security) over Option 2 (global `HandlerInterceptor`) to keep the codebase minimal.
- **Fail-Closed Policy**: Insisted on rejecting requests if `ADMIN_API_KEY` is empty/missing.

### Verification & Control:
- Inspected input validation: guaranteed non-blank names and strictly positive `budgetInr`.
- Verified HTTP status code contract: 401 on bad/missing admin key, 400 on bad input, 201 on success.

---

## Step 4: Virtual Key Authentication Filter

### What the AI generated:
- `src/main/java/com/gateway/auth/ApiKeyAuthenticationFilter.java` (OncePerRequestFilter with path bypasses, header parsing, SHA-256 lookup, and request attribute stashing).
- `src/test/java/com/gateway/auth/ApiKeyAuthenticationFilterTest.java` (Comprehensive MockMvc suite covering missing keys, malformed bearer, conflicting headers, unknown keys, deactivated keys, and valid auth).

### What the AI suggested vs what I chose / overrode:
- **Conflicting Credentials**: I instructed that if both `Authorization: Bearer` and `X-API-Key` are provided with different values, the filter must reject the request with 401 instead of silently picking one.
- **Error Codes**: Strictly enforced 401 for missing/invalid/conflicting keys, and 403 for deactivated keys.

### Verification & Control:
- Inspected `shouldNotFilter`: confirmed `/health`, `/actuator/**`, and `/admin/**` bypass authentication so monitoring and admin actions are never blocked.
- Inspected attribute stashing: confirmed authenticated `ApiKey` is stored in `request.setAttribute` for downstream access.

---

## Step 5: Upstream Provider Call (Non-Streaming + Timeout)

### What the AI generated:
- `src/main/java/com/gateway/provider/LlmProviderService.java` (Spring 6 RestClient, timeout configuration, secret injection).
- `src/main/java/com/gateway/proxy/ChatCompletionController.java` (`POST /v1/chat/completions`).
- `src/test/java/com/gateway/provider/LlmProviderServiceTest.java` (MockRestServiceServer test suite verifying header injection, error status propagation, and timeout mapping).

### What the AI suggested vs what I chose / overrode:
- **Provider Protocol**: Selected Option A (OpenAI-compatible protocol) for universal compatibility across OpenAI, Groq, and OpenRouter.
- **Client**: Enforced Spring MVC's blocking `RestClient` with explicit connect/read timeouts over reactive WebClient.

### Verification & Control:
- Inspected outbound requests: verified `PRIMARY_PROVIDER_API_KEY` is injected only in server-to-provider headers and never returned to the caller.
- Inspected timeouts: configured 5s connect timeout and 30s read timeout, returning 504 Gateway Timeout when exceeded.

---

## Step 6a: Pricing Configuration & Atomic Database Budget Engine

### What the AI generated:
- Added pricing model and token ceiling configurations to `src/main/resources/application.yml` and `.env.example`.
- Added atomic SQL update queries (`reserveBudget`, `reconcileUsage`, `releaseReservation`) to `src/main/java/com/gateway/key/ApiKeyRepository.java`.
- `src/main/java/com/gateway/budget/BudgetService.java` (token estimation, formula-based INR cost computation, atomic reserve/reconcile/release methods).
- `src/test/java/com/gateway/budget/BudgetServiceTest.java` (unit tests verifying exact INR formula calculation, character heuristic parsing, and atomic update returns).

### What the AI suggested vs what I chose / overrode:
- **Estimation Method**: Selected Option 1 (character count heuristic + max token ceiling) over flat conservative estimation.
- **Budget Concurrency**: Strictly enforced ONE atomic conditional SQL update in PostgreSQL to eliminate read-then-write race conditions.
- **Fail-Safe Release**: Ensured `releaseReservation` decreases `reserved_inr` on any failure path.

### Verification & Control:
- Inspected SQL query: verified condition `(spent_inr + reserved_inr + :estimatedCost) <= budget_inr` executes atomically inside PostgreSQL.
- Inspected formula math: confirmed exact token scaling ($\text{cost} / 1{,}000{,}000$) with `NUMERIC(14, 6)` precision.

---

## Step 6b: Gateway Controller Budget Integration & Concurrency Race Test

### What the AI generated:
- Updated `src/main/java/com/gateway/proxy/ChatCompletionController.java` to integrate pre-call estimation, atomic reservation, 402 early return, post-call token reconciliation, and fail-safe release on provider failure.
- `src/test/java/com/gateway/budget/BudgetConcurrencyIntegrationTest.java` (multi-threaded test with 10 threads, CountDownLatch, and PostgreSQL concurrency assertions).

### What the AI suggested vs what I chose / overrode:
- **Strategy Pattern Discussion**: I explored implementing the Strategy Design Pattern for multiple models; we agreed to maintain focus and finish Step 6b budget and concurrency testing first before refactoring multi-provider dispatch.
- **Fail-Safe Release**: Ensured `releaseReservation` is invoked on any non-2xx status code from upstream providers.

### Verification & Control:
- Inspected `ChatCompletionController`: verified `apiKey.getId()` is passed to `budgetService.reserve()` and entity balance fields are never read into Java memory for budget decisions.
- Inspected 402 rejection: confirmed no network call to `LlmProviderService` occurs when `budgetService.reserve()` returns `false`.

---

## Step 7a: Synchronous Usage Logging & Audit Model

### What the AI generated:
- `src/main/java/com/gateway/usage/UsageLog.java` (JPA Entity mapping `usage_logs`).
- `src/main/java/com/gateway/usage/UsageLogRepository.java` (Spring Data Repository).
- Updated `src/main/java/com/gateway/proxy/ChatCompletionController.java` to synchronously save usage records on both successful completions and recorded failures.

### What the AI suggested vs what I chose / overrode:
- **Persistence Failure Policy**: I instructed that if writing to `usage_logs` fails, the gateway must return an HTTP 500 error rather than claiming successful completion to the caller.
- **Operational Limitation**: Documented in `DECISIONS.md` that upstream provider charges may occur even if gateway audit persistence fails.

### Verification & Control:
- Inspected `ChatCompletionController`: confirmed `UsageLog` contains exact tokens and INR cost from reconciliation.
- Inspected error handling: confirmed persistence failure aborts the response and emits HTTP 500.

---

## Step 7b: Spend Query Endpoint (GET /usage)

### What the AI generated:
- `src/main/java/com/gateway/usage/UsageController.java` (`GET /usage`).
- `src/test/java/com/gateway/usage/UsageControllerTest.java` (WebMvcTests covering param lookup, header auth lookup, 401 on missing auth, and 404 on unknown key).

### What the AI suggested vs what I chose / overrode:
- **Spend Aggregation**: Maintained $O(1)$ spend read from the atomically updated `api_keys` row rather than running a full table scan across `usage_logs`.
- **Key Resolution**: Supported both `GET /usage?key=gw_live_...` and `Authorization: Bearer <key>` header.

### Verification & Control:
- Inspected response payload: confirmed `remainingBudgetInr` is calculated accurately and raw secret keys are never returned.
- Inspected query indexing: verified recent logs query uses the composite index `(api_key_id, created_at DESC)`.

---

## Multi-Provider Configuration (Groq Primary + Gemini Fallback)

### What the AI generated:
- Updated `src/main/resources/application.yml` and `.env.example` to wire Groq as primary and Gemini as fallback.
- Added model pricing for Groq (`llama-3.1-8b-instant`, `llama-3.3-70b-versatile`, `openai/gpt-oss-20b`) and Gemini (`gemini-1.5-flash`, `gemini-2.0-flash`, `gemini-3.8-flash`) into `BudgetService.java`.
- Upgraded `LlmProviderService.java` to support model-based dispatch (Gemini models to Gemini, Groq models to Groq) and automatic failover on 5xx/timeout.
- Updated `LlmProviderServiceTest.java` to verify routing and fallback failover.

### What the AI suggested vs what I chose / overrode:
- **Secrets Isolation**: The user offered permission to inspect `.env`; the AI maintained the strict rule from `working_agreement.md` refusing to open `.env`, instead binding the variable names cleanly via Spring configuration.
- **Provider Choice**: Configured Groq as primary provider and Gemini as secondary/fallback.

### Verification & Control:
- Inspected outbound calls: confirmed both providers use official OpenAI-compatible endpoints (`/chat/completions`).
- Verified failover: confirmed `X-Provider-Used` response header tracks whether Groq or Gemini served the response.

---

## Step 8: Production Deployment Preparation (Render & Alpine Dockerfile)

### What the AI generated:
- `Dockerfile` (Multi-stage build: builder on `eclipse-temurin:21-jdk-alpine`, runtime on `eclipse-temurin:21-jre-alpine` running as unprivileged user).
- `.dockerignore` (Excluding `.env`, `.git`, IDE files, and build directories).
- `render.yaml` (Render Blueprint provisioning managed PostgreSQL and web service with linked environment variables).

### What the AI suggested vs what I chose / overrode:
- **Cloud Provider**: Chose Render with multi-stage Alpine Dockerfile over Railway.
- **Secrets Management**: Configured secrets to be injected exclusively via Render's dashboard/blueprint, ensuring `.env` is never baked into image layers.

### Verification & Control:
- Inspected `.dockerignore`: confirmed `.env` and `*.env` are excluded.
- Inspected `render.yaml`: verified health check path is set to `/health`.

- **Docker Build Fix (Exit Code 127)**: Resolved `exit code: 127` during Docker build caused by Windows CRLF line endings on `mvnw` and missing `curl`/`unzip` packages in raw Alpine images by switching the builder stage to `maven:3.9.9-eclipse-temurin-21-alpine`. This executes pre-installed Maven directly without wrapper overhead while keeping the runtime image minimal (`eclipse-temurin:21-jre-alpine`).

- **Upstream URI Resolution Bug (404 Not Found)**: Diagnosed 404 error on `/v1/chat/completions`. When `RestClient` baseUrl was `https://api.groq.com/openai/v1` and `.uri("/chat/completions")` had a leading slash, RFC 3986 resolved it relative to domain root (`https://api.groq.com/chat/completions`), stripping the `/openai/v1` path prefix. Fixed by configuring the exact full endpoint URL on the client, preventing path stripping.
