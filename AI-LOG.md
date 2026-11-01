# AI Collaboration Log

**Author**: Anikesh  
**Project**: Minimal LLM Gateway  
**AI Assistant**: Antigravity (Pair Programmer)  

---

## 1. Tools Used

- **IDE & Development**: Antigravity IDE (Pair programming environment with integrated terminal and tool access).
- **Language & Runtime**: Java 21 (Eclipse Temurin), Spring Boot 3.3.5.
- **Database & Migrations**: PostgreSQL 16, Flyway.
- **Containerization & Hosting**: Docker (multi-stage Alpine builds), Render Blueprint (`render.yaml`).
- **Testing**: JUnit 5, Mockito, Spring Boot Test (`MockMvc`, `MockRestServiceServer`).
- **Command Line**: PowerShell, `curl.exe`.

---

## 2. One Place the AI Was Wrong

### The Upstream URI Resolution Path-Stripping Bug (404 Not Found)
* **What Happened**: During Step 5, when writing the upstream provider client with Spring 6 `RestClient`, the AI configured:
  ```java
  RestClient.builder().baseUrl("https://api.groq.com/openai/v1").build();
  ```
  and executed the request via:
  ```java
  client.post().uri("/chat/completions").body(body).retrieve().toEntity(String.class);
  ```
  In production on Render, every single request to Groq failed with `404 Not Found`.
* **The Root Cause**: Per RFC 3986 URI resolution standards implemented in Spring's `UriComponentsBuilder`, when `.uri("/chat/completions")` has a leading slash, it resolves relative to the **domain root**, silently stripping the `/openai/v1` path prefix and resolving to `https://api.groq.com/chat/completions` instead of `https://api.groq.com/openai/v1/chat/completions`.
* **The Fix**: I caught the missing path segment in the Cloudflare response headers and instructed the AI to configure the full, normalized target URL (`normalized + "/chat/completions"`) directly on the `RestClient` instance and remove the relative URI from `.post()`.

### The Docker Build Failure in Alpine (Exit Code 127)
* **What Happened**: The AI initially generated a `Dockerfile` executing `./mvnw clean package` inside a standard Alpine image (`eclipse-temurin:21-jdk-alpine`). The build immediately failed on Render with:
  `ERROR: process "/bin/sh -c ./mvnw clean package -DskipTests" did not complete successfully: exit code: 127`.
* **The Root Cause**: Git on Windows saved `mvnw` with CRLF carriage returns, breaking the `#!/bin/sh` shebang on Linux. Furthermore, minimal Alpine images do not include `curl` or `unzip`, which the Maven Wrapper requires to download Maven.
* **The Fix**: I instructed the AI to switch Stage 1 of the Dockerfile to `maven:3.9.9-eclipse-temurin-21-alpine` (which pre-packages Maven), executing `mvn clean package` directly without wrapper dependencies.

---

## 3. One Place I Overrode the AI

### Enforcing Strict Fail-Closed Security on Conflicting Headers
* **The Scenario**: During Step 4 (Virtual Key Authentication Filter), the AI proposed accepting virtual keys from either `Authorization: Bearer <key>` or `X-API-Key: <key>`. In its initial draft, if a request supplied both headers with different keys, the code silently preferred the `Authorization` header and ignored `X-API-Key`.
* **My Override**: I rejected this behavior. Silently picking one header when two different credentials are provided introduces security ambiguity and masking of client-side integration bugs. I required the AI to explicitly check if both headers are present and differ, and immediately reject the request with `401 Unauthorized` (`"Conflicting credentials: Authorization and X-API-Key headers do not match"`).

### Refusing to Retry on 4xx Client Errors
* **The Scenario**: In Step 9 (Fallback), the AI initially proposed catching all non-2xx responses from Groq (including 400 Bad Request and 404 Model Not Found) and falling back to Gemini.
* **My Override**: I overrode this design. Client errors are deterministic; if the client submits an invalid JSON payload or illegal parameter, retrying on a secondary provider wastes money and adds latency to a request that is doomed to fail. I enforced that fallback must trigger **strictly on 5xx server errors and socket timeouts**.

---

## 4. How I Stayed in Control

Throughout the take-home, I maintained strict control over the codebase, architecture, and security boundaries:

1. **Zero Secret Exposure**:
   - I ensured that `.env` was added to `.gitignore` and `.dockerignore` before any code was staged or committed.
   - At no point was the AI permitted to read, log, print, or commit raw `.env` files.
   - Provider API keys (Groq and Google Gemini) and database credentials were confirmed to come strictly from host environment variables.
2. **Controlling Budget Concurrency**:
   - I did not allow the AI to use simple application-level `if (spent + cost > budget)` checks.
   - I mandated a single atomic SQL `UPDATE ... WHERE (spent_inr + reserved_inr + :cost) <= budget_inr` and verified it with a 10-thread parallel race test (`BudgetConcurrencyIntegrationTest.java`).
3. **Step-by-Step Discipline**:
   - I strictly enforced the Working Agreement rule: exactly one step at a time, requiring a formal proposal (Goal, Files, Options, Risks) and waiting for my explicit `"go"` before writing code.
   - Draft documentation files were updated after every single step.

---

## 5. One Thing I Learned from Scratch

**PostgreSQL Row-Locking Semantics on Native Conditional Updates**:  
Prior to this project, my instinct for preventing double-spend concurrency issues would have been to use a distributed lock (e.g. Redis Redlock) or pessimistic locking via JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)`. 

Through implementing Step 6, I learned the power and simplicity of atomic conditional SQL updates in PostgreSQL:
```sql
UPDATE api_keys 
SET reserved_inr = reserved_inr + :est 
WHERE id = :id AND (spent_inr + reserved_inr + :est) <= budget_inr;
```
By allowing PostgreSQL's internal MVCC row-level write lock to serialize concurrent updates, the database itself acts as the atomic arbiter. If the condition is met, it returns `1 row affected`; if exhausted, it returns `0 rows affected`. This completely eliminates race conditions without adding external cache infrastructure, extra network hops, or distributed transaction deadlocks.
