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
