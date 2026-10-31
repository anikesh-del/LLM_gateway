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
