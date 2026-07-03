# Architecture

> A navigational map of the FIFA 2026 World Cup predictor. Use it to find **where**
> to investigate a bug or build a feature — not how every method works. For
> micro-details, read the source.

## What This Is

A **Spring Boot 3.4** web app (Java 21) that lets users predict the 2026 World Cup:
simulate the 48-team group stage, auto-advance the 24 qualifiers, play through a
32-team knockout bracket, view/simulate from **Betfair Exchange** odds, and persist
each user's predictions. A vanilla HTML/JS/CSS single-page frontend renders over a
REST API.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime / Framework | Java 21, Spring Boot 3.4 |
| Build | Gradle (Kotlin DSL); multi-stage Dockerfile → Railway.app |
| Persistence | H2 file-based DB (`./data/fifa.*`) via Spring Data JPA |
| Security | Spring Security + OAuth2 Resource Server (Cloudflare Zero Trust JWT in `prod`, mock user otherwise) |
| Env | `spring-dotenv` loads `.env` |
| Frontend | Vanilla HTML/JS/CSS, no framework |

## Project Layout

```
src/main/java/dev/scaffoldkit/fifa/
├── FifaApplication.java        # @SpringBootApplication entry point
├── controller/                 # REST API (web layer)
├── service/                    # Tournament engine (core domain logic)
├── model/                      # Domain value objects & JPA entity
├── repository/                 # Spring Data JPA repositories
├── betfair/                    # Betfair Exchange integration (isolated context)
│   ├── model/                  #   Typed Betfair API POJOs
│   └── deserializer/           #   Custom Jackson deserializers for the above
└── web/                        # Security configs & global exception handler

src/main/resources/
├── application.properties      # Spring config (DB, JWT, Betfair placeholders)
├── annex_c.json                # Precomputed FIFA Annex C 3rd-place matrix (generated)
├── fallback-odds.json          # Snapshotted Betfair odds (prod data source)
└── static/                     # Frontend SPA (index.html, app.js, style.css)

scripts/generate_annex_c.py     # Regenerates annex_c.json from docs/ANNEX_C.txt
ssl/                            # Betfair mTLS client cert/key (not committed)
docs/                           # This file + ANNEX_C.txt source matrix
fifa-*-standalone.jar           # Standalone odds updater (see Betfair context)
```

## Bounded Contexts

### Tournament Engine — `service/` (core domain)

The in-memory, stateless, resettable heart of the app.

| Class | Responsibility |
|-------|---------------|
| `GroupStageService` | 12 groups (A–L) × 4 teams, round-robin matches, standings, qualification (12 winners + 12 runners-up + 8 best 3rd-place teams = 32). |
| `BracketService` | 32-team single-elimination bracket (R32→R16→QF→SF→Final), split into left/right halves. Seeded from group results; winner propagates forward on score change. |
| `ThirdPlaceMatrixService` | Resolves which group winners face which advancing 3rd-place teams via **FIFA Annex C**. Lookups are O(1) against `annex_c.json` (regenerated from `docs/ANNEX_C.txt` by `scripts/generate_annex_c.py`). |
| `ActualResultsService` | Admin-locked **real** group **and knockout** results (file-backed: `actual-results.json`). Locked matches cannot be overwritten by Betfair simulation. |
| `AppEventService` | In-memory ring buffer of INFO/WARNING/ERROR events surfaced to the UI as toast notifications. |

> State model: tournament data lives in memory for speed; only per-user
> predictions and locked results are persisted. The engine is intentionally
> resettable.

### Domain Models — `model/`

`Team` (immutable), `GroupMatch`, `GroupStanding`, `KnockoutMatch` (each wires to
its `nextMatchId`/`nextSlot` so winners propagate), and the `UserProfile` JPA entity.

### Betfair Integration — `betfair/` (isolated context)

Fetches live betting odds to (a) display alongside matches and (b) probabilistically
simulate group-stage scores. **Profile-isolated**: in `prod` (where Betfair IP-blocks
the host) all live-API beans are disabled via `@Profile("!prod")` and the system reads
exclusively from `fallback-odds.json`.

| Concern | Where |
|---------|-------|
| Orchestrator / public facade | `BetfairIntegrationService` (only public class in the package) |
| mTLS auth + market REST clients | `BetfairAuthClient`, `BetfairMarketClient` |
| mTLS `RestTemplate` beans | `BetfairSslConfig` |
| Config (`@ConfigurationProperties`) | `BetfairProperties` (`.env`: `BETFAIR_*`) |
| Name → FIFA-code mapping | `BetfairNamesToCodes` |
| Typed API payload model | `betfair/model/` (POJOs) |
| JSON parsing | `betfair/deserializer/` (custom Jackson deserializers) |
| Periodic local snapshots | `BetfairOddsSnapshotScheduler` — every 13 min, `!prod` only |
| Cache invalidation event | `OddsUpdatedEvent` (Spring application event) |
| Standalone updater (no web server) | `BetfairOddsSnapshotApp` → packaged as `fifa-*-standalone.jar`; snapshots odds locally and pushes them to production |
| Diagnostic: dump runner names | `DumpRunnerNamesRunner` (CLI flag) |

**Odds data flow:** live API (when reachable) → fallback to `fallback-odds.json`
(filesystem, then classpath) → equal-probability default. Snapshots captured locally
(localhost or standalone jar) are uploaded to prod via the admin endpoint.

### Web Layer — `controller/` + `web/`

| Class | Role |
|-------|------|
| `TournamentController` | Primary REST facade: `/api/groups`, `/api/bracket`, `/api/betfair/*`, `/api/admin/*`, `/api/events`. Delegates to the engine + Betfair facade. |
| `UserStateController` | Per-user state load/save (`/api/user/state`) keyed by `@AuthenticationPrincipal`. |
| `ProdSecurityConfig` / `LocalSecurityConfig` | Profile-based security: Cloudflare JWT vs. mock user. Both expose a `UserProfile` principal. |
| `UserProfileJwtAuthenticationConverter` | JWT `email` claim → `UserProfile` (auto-creates on first login). |
| `GlobalExceptionHandler` | Catches controller exceptions → records `AppEventService` events → short HTTP responses. |

### Persistence — `repository/` + files

- `UserProfileRepository` — JPA access to `UserProfile` (email + `predictionsJson`).
- `ActualResultsService` — file-backed locked results (`actual-results.json`).
- `fallback-odds.json` — Betfair odds snapshot (writable at runtime via admin upload).

### Frontend — `src/main/resources/static/`

A thin vanilla SPA over the REST API: group cards with inline score editors, a
scrollable knockout bracket, auto-save (debounced) of user state, admin lock/unlock
controls, Betfair-simulate button, and a notification toast poller (`/api/events`).
Match chronology is driven by a hardcoded `STATIC_MATCH_SCHEDULE` in `app.js`.

## Primary Data Flows

- **Set a score** → `TournamentController` → `GroupStageService`/`BracketService`
  (standings/winner recompute) → frontend debounced auto-save → `UserStateController`.
- **Seed bracket** → `BracketService.seedBracket()` → reads group results +
  `ThirdPlaceMatrixService` → populates R32.
- **Simulate from odds** → `BetfairIntegrationService.simulateGroupStageOdds()`
  (live or fallback) → applies scores, **respecting any locked actual results**.
- **Refresh odds** → scheduled snapshot (`!prod`) or standalone jar → uploads
  `fallback-odds.json` to prod → `OddsUpdatedEvent` invalidates controller caches.

## Key Design Decisions

- **Betfair is fully isolated** — the package degrades gracefully; in `prod` no live
  beans are even loaded. The engine never depends on Betfair.
- **Annex C is data, not code** — the 495-case matrix is precomputed JSON, regenerated
  by a script; no runtime algorithm to get wrong.
- **Hybrid state** — fast in-memory engine + per-user/locked-result persistence.
- **Profile-based security** — uniform `UserProfile` principal across dev and prod.
- **Operable odds pipeline** — scheduled snapshots on localhost, a standalone updater
  jar, and an admin upload endpoint keep production odds fresh without redeploying.

## Where to Look

| Task | Start here |
|------|-----------|
| Group standings / qualification bug | `service/GroupStageService` |
| Knockout seeding / winner propagation | `service/BracketService` |
| Wrong 3rd-place opponent pairing | `service/ThirdPlaceMatrixService` + `annex_c.json` (regenerate via `scripts/generate_annex_c.py`) |
| Locked results not respected | `service/ActualResultsService` |
| REST endpoint / request shape | `controller/TournamentController`, `controller/UserStateController` |
| Auth / principal / permissions | `web/ProdSecurityConfig`, `web/LocalSecurityConfig`, `web/UserProfileJwtAuthenticationConverter` |
| Betfair login, mTLS, or API calls | `betfair/BetfairAuthClient`, `betfair/BetfairMarketClient`, `betfair/BetfairSslConfig` |
| Odds parsing / snapshot shape | `betfair/model/`, `betfair/deserializer/` |
| Odds refresh / upload / fallback file | `betfair/BetfairOddsSnapshotScheduler`, `betfair/BetfairOddsSnapshotApp`, `BetfairIntegrationService` |
| Per-user prediction save/restore | `model/UserProfile`, `repository/UserProfileRepository` |
| UI errors / toasts / events | `service/AppEventService`, `web/GlobalExceptionHandler`, `static/app.js` |
| Frontend rendering / interactions | `static/app.js`, `static/index.html` |
| Env vars / config | `application.properties`, `.env`, `betfair/BetfairProperties` |
| Deployment | `Dockerfile`, `README.md` |
