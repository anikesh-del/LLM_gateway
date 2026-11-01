# Architecture & Design Decisions (Draft)

Working draft of engineering decisions, alternatives considered, chosen approaches, and accepted trade-offs.

---

## 1. Budget Unit & Cost Calculation Formula
- **Context**: The gateway needs to enforce per-key budgets across different LLM models.
- **Options Considered**:
  1. *Request count*: Count total HTTP requests per key. Simple, but ignores model costs and large prompt vs small prompt variances.
  2. *Token count*: Total tokens (prompt + completion). Still problematic because different models (e.g. GPT-4o vs GPT-3.5/mini) have vastly different price points.
  3. *Currency (INR ₹)*: Compute cost per request based on input/output pricing per million tokens:
     $$\text{Total Cost} = \frac{I \times P_i}{1{,}000{,}000} + \frac{O \times P_o}{1{,}000{,}000}$$
- **Decision**: Currency in INR ($\text{₹}$).
- **Trade-offs Accepted**: Requires tracking model pricing and handling fractional currency amounts with fixed-point arithmetic (`NUMERIC` in DB) rather than simple integers, but reflects real-world cloud economics accurately.

---

## 2. Technology Stack Baseline (Step 1)
- **Context**: Project skeleton foundation.
- **Options Considered**:
  1. *Spring Boot + Gradle* vs *Spring Boot + Maven*.
  2. *WebFlux (Reactive)* vs *Spring MVC (Blocking) with Java 21*.
- **Decision**: Maven + Spring MVC with Java 21, Flyway, and PostgreSQL.
- **Trade-offs Accepted**: Avoided reactive complexity since PostgreSQL JDBC/JPA is inherently blocking. Virtual threads can provide high concurrency without reactive callback overhead.


---

## 3. Database Schema & Concurrency Reservation Model (Step 2)
- **Context**: Schema design for virtual keys and request audit logging, supporting atomic pre-reservation and post-call reconciliation.
- **Options Considered**:
  1. *Dual tracking (`spent_inr` + `reserved_inr`)* vs *Single tracking (`spent_inr` only)*:
     - Single tracking conflates in-flight reservations with settled historical spend.
     - Dual tracking maintains a strict boundary between confirmed expenditure and pending provider requests.
  2. *Primary Key*: `UUID` (`gen_random_uuid()`) vs `BIGSERIAL`:
     - UUIDs chosen to avoid sequential ID enumeration attacks on public API keys and usage logs.
  3. *Index Strategy*:
     - Explicit unique index on `key_hash` ensures $O(\log N)$ point lookups in the auth filter on every inbound request.
     - Composite index on `usage_logs(api_key_id, created_at DESC)` ensures efficient aggregation and pagination for spend history endpoints.
- **Decision**: Dual tracking (`spent_inr` and `reserved_inr`) with `NUMERIC(14, 6)` and UUID primary keys.
- **Trade-offs Accepted**: Updating two columns during reservation and reconciliation requires slightly more verbose SQL logic, but yields full visibility into in-flight state and crash recovery.

---

## 4. Virtual Key Security & Hashing Model (Step 3a)
- **Context**: Virtual API keys grant access to the gateway and enforce budgets. If leaked from the gateway database, attackers could exhaust caller budgets.
- **Options Considered**:
  1. *Plaintext Storage*: Storing the raw key in PostgreSQL. Completely unacceptable; any database dump or SQL injection exposes caller keys.
  2. *Symmetric Encryption (AES-GCM)*: Reversible encryption. Still leaves master key management risk and is unnecessary since the gateway only ever needs to verify incoming keys, never recover them.
  3. *Cryptographic One-Way Hashing (SHA-256)*: Irreversible. The raw key is generated, displayed once to the caller, and only the 64-character SHA-256 hex digest is persisted.
- **Key Format Decision**: Prefixed 256-bit token (`gw_live_<64-hex>`). The `gw_live_` prefix allows secret scanners and human developers to identify leaked credentials immediately.
- **Trade-offs Accepted**: If a caller loses their virtual key, it cannot be recovered by the admin—a new key must be issued.

---

## 5. Admin Authentication & Key Issuance Endpoint (Step 3b)
- **Context**: An administrative endpoint `POST /admin/keys` is required to issue virtual keys with assigned budgets. This endpoint must be protected from unauthorized access.
- **Options Considered**:
  1. *Full Spring Security Framework*: Heavy filter chain with session management and user stores. Overkill for a micro-gateway.
  2. *Header-based Secret (`X-Admin-Key`)*: Direct secret comparison loaded from environment variables (`ADMIN_API_KEY`).
- **Decision**: Header-based `X-Admin-Key` with constant-time equality check (`MessageDigest.isEqual`).
- **Fail-Closed Security**: If `ADMIN_API_KEY` is not configured or blank, all `/admin/**` requests are immediately rejected with 401 Unauthorized (never defaults to open).
- **Trade-offs Accepted**: Admin keys are static per environment deployment rather than per-user identities, perfectly matching the minimal scope of this gateway.

---

## 6. Virtual Key Authentication Filter (Step 4)
- **Context**: Intercept inbound proxy requests, extract caller credentials, and validate against stored hashes before passing downstream.
- **Options Considered**:
  1. *OncePerRequestFilter*: Standard Spring Web filter running early in the servlet pipeline before controller resolution.
  2. *HandlerInterceptor*: Spring MVC interceptor executing inside DispatcherServlet.
- **Decision**: `OncePerRequestFilter`. Bypasses `/health`, `/actuator/**`, and `/admin/**`.
- **Extraction & Validation**:
  - Primary: `Authorization: Bearer <key>`.
  - Alternative: `X-API-Key: <key>`.
  - Conflict Resolution: If both are supplied and differ, rejects immediately with 401 Unauthorized rather than silently preferring one.
  - Active Check: If key exists but `is_active = false`, returns 403 Forbidden.
  - Context Propagation: Stashes authenticated `ApiKey` entity directly in `request.setAttribute("AUTHENTICATED_KEY", apiKey)` to avoid redundant DB queries in downstream budget and logging layers.
- **Trade-offs Accepted**: Stashing the entity in request attributes limits scope to the current request thread, which perfectly matches our blocking MVC thread model.

---

## 7. Upstream Provider Dispatch & Timeout Strategy (Step 5)
- **Context**: Forwarding validated chat requests from callers to upstream LLM providers without leaking credentials or hanging connections indefinitely.
- **Options Considered**:
  1. *WebClient / Netty (Reactive)*: Rejected in Step 1 to avoid reactive/JPA concurrency mismatch.
  2. *Spring 6 `RestClient` (Blocking)*: Native fluent blocking HTTP client, fully compatible with Java 21 virtual threads.
- **Protocol**: OpenAI-compatible standard (`POST /v1/chat/completions`). Parameterized `PRIMARY_PROVIDER_BASE_URL` allows seamlessly switching between OpenAI, Groq, or OpenRouter without code changes.
- **Timeout Policy**:
  - Connect timeout: 5,000 ms.
  - Read timeout: 30,000 ms (sufficient for slow generations without permitting thread exhaustion).
- **Error Mapping**:
  - Upstream 4xx/5xx responses: Forwarded transparently to caller without modifying error payload or exposing internal gateway secrets.
  - Upstream socket timeout: Mapped directly to HTTP 504 Gateway Timeout.
  - Network disconnection: Mapped to HTTP 502 Bad Gateway.
- **Trade-offs Accepted**: Non-streaming responses wait for full completion generation before returning, which prioritizes architectural simplicity and exact token accounting before streaming is considered.

---

## 8. Atomic Budget Reservation & Pre-Call Estimation (Step 6a)
- **Context**: Enforcing per-key budgets under high concurrency without permitting overspend races when multiple requests arrive simultaneously for a near-exhausted key.
- **Why Enforce at the Gateway**: Callers are untrusted and cannot self-regulate. Upstream providers bill post-facto and do not enforce custom virtual caller budgets. The gateway is the sole mandatory chokepoint.
- **Race Condition Solution**:
  - *Naive Approach*: `SELECT spent, budget ...` followed by application check and `UPDATE`. Vulnerable to race conditions where two simultaneous requests see sufficient budget and both proceed (double spend).
  - *Chosen Approach*: Single atomic conditional native SQL update:
    ```sql
    UPDATE api_keys
    SET reserved_inr = reserved_inr + :estimatedCost, updated_at = NOW()
    WHERE id = :id AND is_active = TRUE AND (spent_inr + reserved_inr + :estimatedCost) <= budget_inr;
    ```
    PostgreSQL's row-level lock serializes concurrent updates. The database engine returns `1` if the condition was met, or `0` if over budget.
- **Estimation Heuristic**:
  - Input tokens estimated at $\lceil \text{char count} / 4 \rceil$.
  - Output tokens use requested `max_tokens` clamped to `MAX_TOKENS_CEILING` (or `DEFAULT_MAX_TOKENS`).
  - Pre-call estimated cost calculated in INR using formula converted via `USD_INR`.
- **Trade-offs Accepted**: Pre-call token counts are estimated; post-call reconciliation corrects the balance based on exact tokens returned by the provider.

---

## 9. Budget Enforcement Lifecycle & Fail-Safe Reservation Release (Step 6b)
- **Context**: Orchestrating the request lifecycle across authentication, pre-call estimation, atomic reservation, provider dispatch, post-call reconciliation, and error recovery.
- **Enforcement Ordering**:
  1. *Step 1*: Auth filter extracts and hashes virtual key, verifies active status, stashes key ID.
  2. *Step 2*: `BudgetService.estimateCost()` computes pre-call estimated cost based on message length heuristic and max tokens ceiling.
  3. *Step 3*: Atomic SQL update attempts reservation. If exhausted, gateway halts immediately with HTTP `402 Payment Required`. Upstream provider is never contacted.
  4. *Step 4*: Upstream provider call executed.
  5. *Step 5a (Success)*: Exact `usage` tokens parsed from provider JSON response; actual cost calculated in INR; `reconcileUsage()` settles the difference in PostgreSQL.
  6. *Step 5b (Failure/Timeout)*: Fail-safe release query (`releaseReservation()`) resets in-flight `reserved_inr` hold immediately so callers are never billed for dropped or timed-out requests.
- **Concurrency Test Verification**:
  - Validated via a 10-thread parallel race test (`BudgetConcurrencyIntegrationTest`): on a virtual key endowed with budget for exactly 1 call, exactly 1 thread received HTTP 200, exactly 9 threads were rejected with HTTP 402, and final database reservation was 0.

---

## 10. Synchronous Usage Audit Logging & Operational Limitation (Step 7a)
- **Context**: Recording per-request usage metrics (prompt/completion tokens, cost in INR, model, provider, status) for accounting and caller spend inquiries.
- **Persistence Strategy**: Synchronous persistence in PostgreSQL within the request execution thread.
- **Fail-Closed Persistence Policy**: If writing to `usage_logs` fails (e.g. database disk pressure or transient constraint failure), the gateway returns an HTTP 500 error (`{"error": "Gateway persistence error: failed to record usage log"}`) rather than falsely claiming successful completion to the caller.
- **Operational Limitation (Documented)**:
  - *Edge Case*: Because the upstream provider call has already completed successfully and the provider has billed for the generation, if gateway persistence fails immediately afterward, the upstream charge occurred but the gateway fails to persist the audit record and returns an error to the caller.
  - *Why Accepted*: In a minimal architecture without two-phase commit or distributed sagas, synchronous persistence with fail-closed semantics provides the highest audit integrity without the overhead of transactional outbox tables or Kafka message brokers.

---

## 11. Spend Aggregation & Usage Inquiry Contract (Step 7b)
- **Context**: Enabling callers and administrators to query: *"How much has virtual key X spent, what is remaining, and what was its recent usage history?"*.
- **Endpoint**: `GET /usage` (supports both `?key=gw_live_...` query parameter and standard `Authorization: Bearer <key>` header).
- **Calculation Strategy**:
  - `spentInr` and `reservedInr` are read from the `api_keys` row ($O(1)$ read), ensuring instantaneous aggregation without scanning millions of historical rows.
  - `remainingBudgetInr = MAX(0, budgetInr - (spentInr + reservedInr))`.
  - Detailed request history is paginated via `usage_logs` using the composite index `(api_key_id, created_at DESC)`.
- **Trade-offs Accepted**: Balances are maintained atomically during request processing, allowing the spend query endpoint to be exceptionally fast and lightweight.

---

## 12. Deployment Topology & Container Architecture (Step 8)
- **Context**: Deploying the gateway to a live public URL with automated TLS, managed database persistence, and isolated secrets.
- **Hosting Platform**: Render via Blueprint (`render.yaml`).
  - Automatically provisions a free-tier managed PostgreSQL instance alongside the containerized web service.
  - Linked database credentials (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) injected directly by the platform, eliminating manual connection string maintenance.
- **Container Strategy**: Multi-stage Alpine Dockerfile.
  - *Build Stage*: Compiles the application using the Maven Wrapper on `eclipse-temurin:21-jdk-alpine`.
  - *Runtime Stage*: Runs exclusively on `eclipse-temurin:21-jre-alpine` (~180MB total size).
  - *Container Security*: Runs under an unprivileged user (`gatewayuser`) rather than root.
  - *Dynamic Port Binding*: Binds to Render's dynamic `$PORT` environment variable.
  - *Health Probing*: Monitored via `/health` endpoint to automate zero-downtime rolling deploys.
- **Trade-offs Accepted**: Free tier databases spin down after inactivity; handled by Spring Boot's connection pool reconnection and Flyway running baseline validations on startup.
