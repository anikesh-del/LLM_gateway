# Project Context: Minimal LLM Gateway (Backend Intern Take-Home)

## The assignment (summary)
Build a small HTTP service that sits between callers and LLM providers, adding virtual keys,
per-key budgets, usage/spend logging, and graceful fallback. Deploy to a real URL.
Budget: ~10-14 focused hours, 2 days. A small thing that fully works + sharp DECISIONS.md
beats a big thing that half-works. Cutting scope is expected; say what and why.
Graded on: backend correctness, system design, first-principles reasoning, prod-readiness,
and how well I direct AI and stay in control. UI is NOT scored.

## Must-haves
1. Proxy endpoint forwarding chat requests to a real provider (streaming OR non-streaming: pick one, justify).
2. Virtual API keys. Callers never see provider keys. Provider keys server-side only, never in repo.
3. Per-key budget (requests, tokens, or cost). Over budget -> actually block (429/402).
4. Persist per-request usage: key, model, tokens in/out, estimated cost, timestamp. Must answer "how much has key X spent?".
5. Fallback/resilience: retry, second provider/mock, or fail fast. Defend the policy.
6. Deploy to a real URL, secrets via host env config.

## Deliverables
- Deployed URL + working curl example
- Source repo
- DECISIONS.md (most important): what I built; request lifecycle; 3-5 key decisions
  (options/pick/tradeoff); why enforce budgets at the gateway; concurrency on a near-exhausted key;
  fallback policy; what I did NOT build; least-confident decision (both sides); where it breaks + one more week.
- AI-LOG.md: tools used; one place AI was wrong; one place I overrode it; how I stayed in control
  (secrets, budget, provider call); one thing learned from scratch.
- Optional: <=5 min screen recording.

## Chosen stack
Java 21, Spring Boot, PostgreSQL, Flyway, Docker. Deploy on Render or Railway.
Decided adjustments (to fit the time budget and keep correctness):
- Spring MVC (blocking) + RestClient, NOT WebFlux. JPA is blocking; mixing it with reactive adds risk.
  Use virtual threads (Java 21) if concurrency matters.
- Non-streaming first. Streaming is optional, only after core works. (Pick one and justify in DECISIONS.md.)
- Budget enforced in PostgreSQL with an atomic check-and-reserve, then reconcile with actual usage.
- Redis rate limiting is NOT core (the assignment asks for budgets, not rate limits). Add only if time remains.
- Fallback: bounded retry on timeout/5xx only (never 4xx), then second provider or a mock provider.
- Cheap-vs-capable model router = my ONE allowed stretch item. Only after the core fully works.

## Request lifecycle (target)
client -> API key filter (hash + lookup) -> budget reserve (atomic SQL UPDATE ... WHERE spent + est <= cap)
-> provider call (timeout, bounded retry) -> on failure: fallback provider/mock
-> reconcile actual cost (release over-reservation) -> insert usage row -> response.
Over budget -> 402/429 before any provider call. Failed provider call -> release the reservation.

## Key concepts I must be able to explain
- Why budgets live at the gateway: callers can't be trusted; it's the only mandatory chokepoint.
- Race: two requests read "Rs 1 left" and both pass. Fix: single atomic conditional UPDATE, not read-then-write.
- Cost: exact tokens known only AFTER the call, so reserve an estimate first, then correct.
- Keys: random 256-bit, shown once, only SHA-256 hash stored.
- Retries: only transient errors; bounded; watch double billing and added latency.

## Build steps (one at a time, wait for my "go" between each)
1. Project skeleton: Spring Boot, Postgres via docker-compose, Flyway, health endpoint, .gitignore/.env.example
2. Schema: api_keys, usage_logs (migration only; I choose columns/budget unit)
3. Key creation endpoint (admin-protected) + hashing; key shown once
4. Auth filter: validate virtual key, reject invalid/disabled
5. Provider call: single provider, non-streaming, env-based secret, timeout
6. Budget: estimate, atomic reserve, reconcile, 402 on exhaustion (+ test for the race)
7. Usage logging + GET /usage?key=... (spend per key)
8. FIRST DEPLOY (do this early to catch deployment issues)
9. Fallback: bounded retry + second provider or mock; release reservation on failure
10. DECISIONS.md and AI-LOG.md finalized from drafts, curl example in README
11. STRETCH (only if 1-10 are solid): model router; or streaming; or Redis rate limit
Steps 1-8 are non-negotiable. 9-10 are required. 11 is optional.

## Explicitly NOT building
RBAC/multi-tenant, Kafka, microservices, semantic caching, ML routing, local models, fine-tuning, tracing, WebSockets.

## Open decisions (I decide, you present options)
Budget unit (requests/tokens/Rs), cost estimation method, sync vs async logging, how closely to mimic
OpenAI's schema, which provider(s), mock vs real fallback, whether to cache, whether to write tests.

## Progress (update ONLY after I approve a step)
- [x] 1  - [x] 2  - [x] 3  - [x] 4  - [x] 5  - [x] 6
- [x] 7  - [x] 8  - [x] 9  - [x] 10 - [ ] 11