# FIFA 2026 World Cup — Project Architecture & API Reference

> **Audience:** This document is written for both **humans** and **LLMs** to quickly understand the project's purpose, structure, classes, and integrations.

---

## 1. What Is This Project?

A **Spring Boot 3.4 web application** that serves as an interactive **FIFA 2026 World Cup tournament predictor**. It lets users:

- **Simulate the group stage** — 12 groups (A–L) of 4 teams playing round-robin (72 group matches total).
- **Predict advancement** — Automatically computes which 24 teams advance (12 group winners + 12 runners-up + 8 best third-place teams) using FIFA's official tie-breaking rules and the **Annex C third-place matrix**.
- **Play through the knockout bracket** — A 32-team single-elimination bracket (R32 → R16 → QF → SF → Final), seeded from group results, with automatic winner propagation.
- **Speculate on paths to the final** — Users set hypothetical scores and watch how the bracket reshapes, exploring different tournament scenarios.
- **View live Betfair odds** — An integration with the Betfair Exchange API fetches real-world betting market data for tournament matches.
- **Simulate group results from odds** — Uses Betfair odds (live or fallback) to probabilistically generate match scores.
- **Persist per-user predictions** — Each user's tournament state is saved to an H2 database and restored on page load.
- **Lock actual results** — An admin user can lock in real-world match results, preventing them from being overwritten by Betfair simulation.

The frontend is a single-page vanilla HTML/JS/CSS app served from `src/main/resources/static/`.

---

## 2. Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 |
| Framework | Spring Boot 3.4.1 |
| Build | Gradle (Kotlin DSL) |
| Database | H2 (file-based, persisted to `./data/fifa.*`) |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security + OAuth2 Resource Server (Cloudflare Zero Trust JWT in prod; mock user in dev) |
| SSL/TLS | Bouncy Castle (`bcpkix-jdk18on:1.80`) for PEM key parsing |
| Env Config | `spring-dotenv:4.0.0` — loads `.env` into Spring environment |
| Frontend | Vanilla HTML + CSS + JavaScript (no framework) |
| Testing | JUnit 5 + Spring Boot Test |
| Deployment | Docker multi-stage build, Railway.app with persistent volume |

---

## 3. Project Structure

```
src/main/java/dev/scaffoldkit/fifa/
├── FifaApplication.java                 # @SpringBootApplication entry point
├── betfair/
│   ├── BetfairAuthClient.java           # Mutual-TLS login → session token
│   ├── BetfairMarketClient.java         # Exchange Betting API (catalogue + odds)
│   ├── BetfairIntegrationService.java   # Top-level orchestrator (runs on startup)
│   ├── BetfairProperties.java           # @ConfigurationProperties (apiKey, user, pass, certPath)
│   ├── BetfairNamesToCodes.java         # Betfair runner name → FIFA team code mapping
│   ├── BetfairSslConfig.java            # Builds mTLS RestTemplate beans from PEM files
│   └── DumpRunnerNamesRunner.java       # CLI diagnostic: dumps Betfair team names to file
├── controller/
│   ├── TournamentController.java        # REST API (/api/*) — tournament + Betfair endpoints
│   └── UserStateController.java         # REST API (/api/user/*) — per-user state persistence
├── model/
│   ├── Team.java                        # Immutable team value object (code, name, group, flag)
│   ├── GroupMatch.java                  # Group-stage match with mutable scores
│   ├── GroupStanding.java               # Tracks W/D/L, GF/GA, GD, points; implements Comparable
│   ├── KnockoutMatch.java               # Knockout match with next-match wiring
│   └── UserProfile.java                # JPA entity: user predictions persisted to H2
├── repository/
│   └── UserProfileRepository.java       # Spring Data JPA repository for UserProfile
├── service/
│   ├── ActualResultsService.java        # Locked actual match results (persisted to JSON file)
│   ├── AppEventService.java             # In-memory event log for UI notifications (INFO/WARNING/ERROR)
│   ├── GroupStageService.java           # 12 groups × 4 teams, standings, advancement
│   ├── BracketService.java              # 32-team knockout bracket (left/right halves)
│   └── ThirdPlaceMatrixService.java     # FIFA Annex C 3rd-place assignment algorithm
└── web/
    ├── GlobalExceptionHandler.java      # @RestControllerAdvice: catches exceptions → UI notifications
    ├── LocalSecurityConfig.java         # Dev security: permits all, mock @AuthenticationPrincipal
    ├── ProdSecurityConfig.java          # Prod security: Cloudflare Zero Trust JWT validation
    └── UserProfileJwtAuthenticationConverter.java  # JWT → UserProfile principal converter

src/main/resources/
├── application.properties               # Spring config (env var references, Cloudflare JWT)
├── fallback-odds.json                   # Pre-saved Betfair odds snapshot for production fallback
└── static/
    ├── index.html                       # Single-page app shell
    ├── app.js                           # Vanilla JS frontend logic (~590 lines)
    └── style.css                        # Dark-themed styling

ssl/
├── openssl.cnf                          # OpenSSL config for generating Betfair client certs
├── client-2048.crt                      # Client certificate (not committed)
└── client-2048.key                      # Private key (not committed)

docs/
└── ARCHITECTURE.md                      # ← This file

Root-level files:
├── BETFAIR.md                           # Betfair API setup instructions
├── README.md                            # Setup + Railway.app deployment guide
├── Dockerfile                           # Multi-stage Docker build
├── .env.example                         # Template for environment variables
├── betfair-runner-names.txt             # Dump of Betfair team names (diagnostic output)
```

---

## 4. Tournament Engine

### 4.1 `GroupStageService`

**Purpose:** Manages the 12 groups (A–L), each containing 4 teams playing round-robin (6 matches per group, 72 total). Tracks standings, computes group rankings, and determines which teams advance.

| Aspect | Detail |
|--------|--------|
| Teams | 48 teams hardcoded (FIFA codes like `BRA`, `ENG`, `MEX`) |
| Groups | `A`–`L`, each with exactly 4 teams |
| Matches | 6 per group, IDs like `A1`, `A2`, … `L6`. Home/Away follows the official FIFA 2026 fixture schedule (reflected in Betfair's `sortPriority` field). |
| Standings sorting | Points desc → Goal Difference desc → Goals For desc |
| Advancement | Top 2 per group (24 teams) + 8 best 3rd-place teams = 32 |

**Key Methods:**
- `setGroupMatchScore(matchId, score1, score2)` — sets a score and recalculates all standings
- `getSortedStandings(group)` — returns standings ranked best-first
- `getAllGroupWinners()` / `getAllRunnersUp()` / `getAllThirdPlaces()` — maps of group → team code
- `getBestThirdPlaceGroups()` — the 8 groups whose 3rd-place teams advance
- `getBestThirdPlaceTeamCodes()` — the actual team codes (not group letters)
- `getMatchesForGroup(group)` — returns matches for a group (sorted chronologically when served via API)
- `resetAll()` — clears all scores

### 4.1.1 Match Metadata Enrichment

Group matches can be enriched with Betfair metadata (match date and odds) via `BetfairIntegrationService.enrichMatchesWithBetfairData()`. This is triggered lazily by the `TournamentController` on the first API call that needs match data, and re-triggered after Betfair simulation.

**Enriched fields on `GroupMatch`:**
| Field | Type | Source |
|-------|------|--------|
| `matchDate` | `String` | Betfair `marketStartTime` (ISO-8601, e.g. `"2026-06-11T19:00:00.000Z"`) |
| `odds1` | `Double` | Best back price for team1 (home or away, aligned to GroupMatch order) |
| `oddsDraw` | `Double` | Best back price for the draw |
| `odds2` | `Double` | Best back price for team2 |

**API responses** include these fields when available (omitted when `null`). Matches within each group are sorted chronologically by `matchDate` in the API responses.

### 4.2 `BracketService`

**Purpose:** Manages the 32-team single-elimination knockout bracket (R32 → R16 → QF → SF → Final). Split into **left** and **right** halves to avoid same-group rematches in early rounds.

**Bracket Structure:**
```
LEFT SIDE (16 teams)                    RIGHT SIDE (16 teams)
  8 × R32 → 4 × R16 → 2 × QF → 1 × SF   8 × R32 → 4 × R16 → 2 × QF → 1 × SF
                      ↘                                ↙
                        FINAL (winner of left SF vs right SF)
```

**R32 Seeding Template** (avoids same-group rematches):
```
LEFT:
  M0: W_A vs 3rd(matrix)    M4: W_E vs 3rd(matrix)
  M1: W_C vs 3rd(matrix)    M5: W_G vs 3rd(matrix)
  M2: W_B vs 2nd_D          M6: 2nd_B vs 2nd_H
  M3: W_D vs 2nd_A          M7: 2nd_F vs 2nd_J

RIGHT:
  M8:  W_I vs 3rd(matrix)   M12: W_F vs 3rd(matrix)
  M9:  W_K vs 3rd(matrix)   M13: W_H vs 3rd(matrix)
  M10: W_J vs 2nd_G         M14: 2nd_E vs 2nd_C
  M11: W_L vs 2nd_I         M15: 2nd_K vs 2nd_L
```

**Key Methods:**
- `seedBracket()` — populates R32 from group results + third-place matrix
- `setKnockoutScore(matchId, score1, score2)` — sets score, clears forward chain, advances winner
- `resetAndReseed()` — rebuilds bracket from scratch

### 4.3 `ThirdPlaceMatrixService`

**Purpose:** Implements the **FIFA 2026 Annex C** algorithm. Given the 4 groups whose 3rd-place teams are eliminated, computes which group winners face which advancing 3rd-place teams in the R32. Uses a rotation-based derangement that guarantees no group winner faces its own group's 3rd-place team.

**Key Method:**
- `lookup(eliminatedGroups)` → `MatrixResult` containing 8 `ThirdPlaceSlot(winnerGroup, thirdPlaceGroup)` pairs

### 4.4 Models

| Model | Purpose | Mutable? |
|-------|---------|----------|
| `Team` | Value object: code, name, group, flag | No (all fields `final`) |
| `GroupMatch` | Group-stage match: id, group, team1/team2, score1/score2 | Scores are mutable |
| `GroupStanding` | Tracks played, won, drawn, lost, GF, GA, GD, points | Mutable; implements `Comparable` for ranking |
| `KnockoutMatch` | Knockout match: id, round, side, matchIndex, team1/team2, scores, nextMatchId, nextSlot | Mutable; winner propagates to next match |
| `UserProfile` | JPA entity: id, email, predictionsJson, updatedAt — persisted to H2 | Mutable; managed by Spring Data JPA |

---

## 5. REST API Endpoints

All endpoints are under `/api`. The controllers are `TournamentController` and `UserStateController`.

### 5.1 Tournament Endpoints (`TournamentController`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/api/teams` | All 48 teams with code, name, group, flag |
| `GET` | `/api/groups` | All 12 groups with team code lists |
| `GET` | `/api/groups/{group}` | Single group: teams, standings, matches |
| `GET` | `/api/group-matches` | All 72 group matches with scores |
| `POST` | `/api/groups/{matchId}/score` | Set group match score. Body: `{"score1": int, "score2": int}` |
| `GET` | `/api/standings` | Standings for all groups |
| `GET` | `/api/advancement` | Which teams advance (winners, runners-up, best 3rd, eliminated groups) |
| `GET` | `/api/bracket` | Full knockout bracket with match details |
| `POST` | `/api/bracket/seed` | Seed bracket from current group results |
| `POST` | `/api/bracket/{matchId}/score` | Set knockout score. Body: `{"score1": int, "score2": int}` |
| `POST` | `/api/reset` | Reset all group scores and bracket |
| `GET` | `/api/admin/snapshot-odds` | Snapshot Betfair odds to `fallback-odds.json` (non-prod only) |
| `POST` | `/api/admin/odds/upload` | **Admin only.** Upload raw Betfair JSON as the new `fallback-odds.json` to the persistent volume |
| `GET` | `/api/fallback-odds-timestamp` | Returns the last-modified timestamp of `fallback-odds.json` formatted in Finnish 24h time (e.g. `"19.6. 13:23:50"`) |
| `POST` | `/api/betfair/simulate-groups` | Simulate all group matches using Betfair odds (live or fallback). Respects locked actual results. |
| `GET` | `/api/events` | Returns recent app events (errors, warnings, info) for UI notifications |
| `POST` | `/api/admin/lock-score/{matchId}` | **Admin only.** Lock a group match result. Body: `{"score1": int, "score2": int}` |
| `DELETE` | `/api/admin/lock-score/{matchId}` | **Admin only.** Unlock a group match result |

### 5.2 User State Endpoints (`UserStateController`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/api/user/state` | Returns the current user's saved tournament state as JSON |
| `POST` | `/api/user/state` | Saves the request body as the current user's tournament state |

Both endpoints use `@AuthenticationPrincipal UserProfile` to identify the user. The `POST` endpoint looks up any existing `UserProfile` by email before saving, so that JPA performs an `UPDATE` rather than an `INSERT` (important because the mock auth filter creates a transient entity with `id=null` on every request).

**`GET /api/user/state` response format:**
```json
{
  "email": "user@example.com",
  "isAdmin": false,
  "state": { "groups": {...}, "bracket": {...} },
  "lockedMatches": { "A1": [2, 1], "B3": [0, 0] }
}
```

- `isAdmin` — `true` if the user's email matches the hardcoded admin (`jukkamic@gmail.com`)
- `lockedMatches` — Map of matchId → [score1, score2] for all admin-locked actual results

---

## 6. Authentication & Security

### 6.1 Overview

The application uses **Spring Security** with a **profile-based** approach: a production profile validates Cloudflare Zero Trust JWTs, while local development uses a mock authentication filter. Both approaches set up a `UserProfile` as the Spring Security principal, so controllers can use `@AuthenticationPrincipal UserProfile` uniformly.

> **Note:** The previous architecture used `LocalhostUserFilter` (a `OncePerRequestFilter`) + `UserContext` (a `ThreadLocal` holder). These have been removed and replaced by proper Spring Security integration.

### 6.2 Production Security — `ProdSecurityConfig`

**Profile:** `prod`

**Purpose:** Validates every `/api/**` request using **Cloudflare Zero Trust** JWT tokens.

| Aspect | Detail |
|--------|--------|
| Security | `@EnableWebSecurity`, CSRF disabled, stateless sessions |
| Auth requirement | `/api/**` requires authentication; everything else is permitted |
| JWT source | `Cf-Access-Jwt-Assertion` header (via custom `BearerTokenResolver`) |
| JWK set | `https://scaffoldkit.cloudflareaccess.com/cdn-cgi/access/certs` |
| Principal | JWT's `email` claim → `UserProfile` (looked up or auto-created in H2) |

**Flow per request:**
```
Incoming HTTP request with Cf-Access-Jwt-Assertion header
        │
        ▼
Spring Security OAuth2 Resource Server
        │
        ├── Validate JWT against Cloudflare's JWK set
        │
        ├── UserProfileJwtAuthenticationConverter.convert(jwt)
        │     ├── Extract email from JWT claim
        │     ├── Lookup UserProfile by email (or create + save)
        │     └── Return UserProfileAuthenticationToken(profile, tokenValue)
        │
        └── Controller receives @AuthenticationPrincipal UserProfile
```

### 6.3 Development Security — `LocalSecurityConfig`

**Profile:** `!prod` (default)

**Purpose:** Allows all requests without JWT validation and injects a mock `UserProfile` for `testuser@example.com`.

| Aspect | Detail |
|--------|--------|
| Security | `@EnableWebSecurity`, CSRF disabled, all requests permitted |
| Auth mechanism | `MockAuthenticationFilter` (inner `OncePerRequestFilter`) |
| Mock user | `testuser@example.com` with `ROLE_USER` authority |
| Principal | Loads persisted `UserProfile` from H2 database (or creates transient for first-time users) |

> **Note:** The `MockAuthenticationFilter` looks up the user's profile (including saved `predictionsJson`) from the H2 database via `UserProfileRepository.findByEmail()`. If no persisted profile exists yet (first-time user), it falls back to a transient `new UserProfile(MOCK_EMAIL, "{}")`. This ensures that user state saved via `POST /api/user/state` is correctly returned by `GET /api/user/state`, enabling state restoration after a server restart.

### 6.4 `UserProfileJwtAuthenticationConverter`

A Spring `Converter<Jwt, AbstractAuthenticationToken>` that:
1. Extracts the `email` claim from the validated JWT
2. Looks up the `UserProfile` by email via `UserProfileRepository`
3. If not found, auto-creates and saves a new `UserProfile` with blank predictions
4. Returns a `UserProfileAuthenticationToken` (inner class carrying the profile + token)

### 6.5 Database — H2 (File-Based)

| Aspect | Detail |
|--------|--------|
| Database | H2 embedded, persisted to `./data/fifa.*` files |
| DDL mode | `spring.jpa.hibernate.ddl-auto=update` (auto-creates/updates tables) |
| Connection | `jdbc:h2:file:./data/fifa`, username `sa`, no password |
| Console | H2 web console enabled at `http://localhost:8080/h2-console` |
| Dialect | `org.hibernate.dialect.H2Dialect` |

> **Production note:** On Railway.app, the datasource URL is overridden to `jdbc:h2:file:/app/data/predictions` with a persistent volume mounted at `/app/data`.

### 6.6 JPA Entity — `UserProfile`

| Field | Type | Column | Constraints |
|-------|------|--------|-------------|
| `id` | `Long` | `id` | `@Id`, `@GeneratedValue(IDENTITY)` |
| `email` | `String` | `email` | `NOT NULL`, `UNIQUE` |
| `predictionsJson` | `String` | `predictions_json` | `@Lob`, `CLOB` |
| `updatedAt` | `Instant` | `updated_at` | — |

### 6.7 Repository — `UserProfileRepository`

A standard Spring Data `JpaRepository<UserProfile, Long>` with one derived query:

| Method | Returns | Purpose |
|--------|---------|---------|
| `findByEmail(String email)` | `Optional<UserProfile>` | Look up a user by email address |

All standard `JpaRepository` methods (`save`, `findById`, `findAll`, `delete`, etc.) are inherited.

---

## 7. Betfair Exchange Integration

### 7.1 Overview

The `betfair` package integrates with the **Betfair Exchange API** to fetch live betting odds for soccer matches. This serves two purposes:

1. **Display live odds** alongside the tournament predictor for comparison.
2. **Simulate group stage results** using real market probabilities.

The integration uses **mutual TLS (mTLS) certificate-based authentication** (Betfair's non-interactive login flow), which requires a client certificate and private key stored in the `ssl/` directory.

**Production profile:** The Betfair API is **permanently blocked** from Railway.app hosting (IP restrictions). To avoid unnecessary connection errors, all live Betfair beans (`BetfairSslConfig`, `BetfairAuthClient`, `BetfairMarketClient`, `BetfairProperties`, `DumpRunnerNamesRunner`) are annotated with `@Profile("!prod")` — they are **not loaded** when the `prod` profile is active. In production, `BetfairIntegrationService` operates in **fallback-only mode**, reading exclusively from `fallback-odds.json`. This means:
- No mTLS SSL context is built at startup
- No authentication attempt is made
- No live API calls are issued
- The Docker container does not need SSL certificate environment variables

**Fallback mechanism:** When Betfair is unreachable (or in production where live API is disabled), the system falls back to a pre-saved odds snapshot (`fallback-odds.json`). See §7.8 for details.

### 7.2 Authentication Flow

```
┌─────────────────┐    POST (mTLS)     ┌──────────────────────────────────────┐
│ BetfairAuthClient├───────────────────►│ identitysso-cert.betfair.com        │
│                  │  form: user+pass   │ /api/certlogin                      │
│                  │◄───────────────────│                                      │
└────────┬─────────┘  sessionToken      └──────────────────────────────────────┘
         │
         ▼
┌─────────────────────────┐
│ sessionToken (cached)   │
│ in BetfairIntegration   │
│ Service                 │
└─────────────────────────┘
```

**Class: `BetfairAuthClient`** (package-private `@Component`)
- Sends a `POST` to `https://identitysso-cert.betfair.com/api/certlogin`
- Headers: `Content-Type: application/x-www-form-urlencoded`, `X-Application: {apiKey}`
- Body: form-encoded `username` + `password`
- Uses the `betfairAuthRestTemplate` bean (mTLS-configured, 10s connect / 15s read timeout)
- Returns the `sessionToken` string on success, `null` on failure
- Parses JSON response: checks `loginStatus == "SUCCESS"`, extracts `sessionToken`

### 7.3 Market Data API

**Class: `BetfairMarketClient`** (package-private `@Component`)

Communicates with the **Betfair Exchange Betting REST API** at:
`https://api.betfair.com/exchange/betting/rest/v1.0`

**Two operations:**

| Method | API Endpoint | Purpose |
|--------|-------------|---------|
| `listMarketCatalogue(sessionToken)` | `/listMarketCatalogue/` | Discovers soccer match-odds markets |
| `listMarketCatalogue(sessionToken, textQuery)` | `/listMarketCatalogue/` | Same, but with a text filter |
| `listMarketBook(sessionToken, marketIds)` | `/listMarketBook/` | Fetches current best back/lay prices |

**`listMarketCatalogue` details:**
- Filters by Soccer Event Type ID `1`
- Only `MATCH_ODDS` market type
- **Hardcoded FIFA World Cup competition ID** `12469077`
- Optional `textQuery` parameter for name-based filtering
- Returns up to 100 markets
- Projections: `COMPETITION`, `EVENT`, `EVENT_TYPE`, `MARKET_START_TIME`, `RUNNER_DESCRIPTION`

**`listMarketBook` details:**
- Fetches for specific market IDs
- Price projection: `EX_BEST_OFFERS` with depth 3 (top 3 back/lay prices per runner)
- Uses `betfairApiRestTemplate` (10s connect / 30s read timeout)

**Common API Headers** (set by `apiHeaders()`):
- `X-Application: {apiKey}`
- `X-Authentication: {sessionToken}`
- `Content-Type: application/json`
- `Accept: application/json`

### 7.4 Betfair Name Mapping — `BetfairNamesToCodes`

**Purpose:** Maps Betfair's display names (e.g., `"Ivory Coast"`, `"Türkiye"`, `"Cape Verde"`) to the application's internal 3-letter FIFA codes (e.g., `CIV`, `TUR`, `CPV`).

- Static `Map<String, String>` with ~47 entries
- Used by `BetfairIntegrationService.simulateGroupStageOdds()` to match Betfair runners to group matches
- Hand-curated; can be refreshed via `DumpRunnerNamesRunner`

### 7.5 Orchestration — `BetfairIntegrationService`

**Purpose:** Top-level `@Service` that orchestrates the full Betfair pipeline. The only public class in the `betfair` package. Active in **all profiles** but behaves differently depending on whether live API beans are available.

**Dependency injection:** Uses Spring `ObjectProvider<>` to optionally inject `BetfairAuthClient` and `BetfairMarketClient`. In the `prod` profile, these beans are absent (disabled via `@Profile("!prod")`), so `liveApiAvailable` is `false` and all live API calls are skipped.

**Startup Sequence** (`@PostConstruct`):
- **Non-prod profiles:** Attempts authentication → fetches market catalogue → logs results.
- **Prod profile:** Logs that live API is disabled and exits immediately — no network calls.

If authentication fails (non-prod), the app continues without live odds (graceful degradation).

**Public API:**
- `authenticate()` → `boolean` — performs login and caches session token
- `fetchMarketCatalogue()` → `String` (raw JSON) — discovers markets
- `fetchMarketBook(marketIds)` → `String` (raw JSON) — gets odds for specific markets
- `getSessionToken()` → `String` — returns cached token
- `snapshotOddsLocally()` → `void` — fetches live odds, writes to `fallback-odds.json`, and pushes to production server via `POST /api/admin/odds/upload` (authenticated with Cloudflare JWT)
- `simulateGroupStageOdds(groupMatches)` → `Map<String, int[]>` — simulates all 72 group matches using odds
- `enrichMatchesWithBetfairData(groupMatches)` → `void` — populates `matchDate`, `odds1`, `oddsDraw`, `odds2` on each GroupMatch from Betfair market data (live or fallback)
- `collectWorldCupRunnerNames()` → `Set<String>` — diagnostic: collects all team names from Betfair

### 7.6 Diagnostic Tool — `DumpRunnerNamesRunner`

**Purpose:** A `CommandLineRunner` that collects all Betfair runner (team) names from World Cup MATCH_ODDS markets and writes them to `betfair-runner-names.txt`.

**Activation:**
```
.\gradlew.bat bootRun --args="--betfair.dump-runner-names=true"
```

The app starts, writes the file, prints results, and exits. Used to refresh `BetfairNamesToCodes` when Betfair changes team name spellings. Marked as temporary in source comments.

### 7.7 SSL Configuration — `BetfairSslConfig`

**Profile:** `!prod` (disabled in production — not loaded when `prod` is active)

**Purpose:** Creates two `RestTemplate` beans configured with mutual TLS, using the Betfair client certificate and private key.

**Certificate files** (loaded from `{certPath}/ssl/`):
- `client-2048.crt` — X.509 client certificate
- `client-2048.key` — PEM private key (PKCS#1 or PKCS#8)

**Process:**
1. Parses the X.509 certificate using `CertificateFactory`
2. Parses the PEM private key using **Bouncy Castle** (`PEMParser` + `JcaPEMKeyConverter`)
3. Creates an in-memory **PKCS#12 keystore** with the cert and key
4. Builds an `SSLContext` from the keystore for mutual TLS
5. Creates a `java.net.http.HttpClient` with the SSL context
6. Wraps it in a `RestTemplate` via `JdkClientHttpRequestFactory`

**Beans produced:**

| Bean Name | Purpose | Timeouts |
|-----------|---------|----------|
| `betfairAuthRestTemplate` | SSO certlogin endpoint | 10s connect, 15s read |
| `betfairApiRestTemplate` | Exchange betting API | 10s connect, 30s read |

### 7.8 Fallback Odds — File-Based Betfair Data

#### Problem

Betfair restricts API access from certain IP addresses, including common cloud hosting providers (Hetzner, AWS, etc.). When the application runs on a cloud server, Betfair API calls may fail with connection errors or `BETTING_RESTRICTED_LOCATION`. The application needs to handle this gracefully.

#### Solution: `fallback-odds.json`

A pre-saved snapshot of Betfair market data, stored in the **persistent data directory** (`{app.data.dir}/fallback-odds.json` — e.g. `/app/data/fallback-odds.json` in prod, `./data/fallback-odds.json` in dev). This allows the file to be updated at runtime without redeploying. A copy is also bundled as a classpath resource at `src/main/resources/fallback-odds.json` as a bootstrapping fallback for fresh volumes. Contains both the market catalogue and market book data in a single JSON object, along with an embedded snapshot timestamp:

```json
{
  "snapshotTimestamp": "2026-06-12T04:47:00Z",
  "catalogue": [ { "marketId": "...", "runners": [...], "event": {...}, ... } ],
  "books": [ { "marketId": "...", "runners": [ { "ex": { "availableToBack": [...], "availableToLay": [...] } } ] } ]
}
```

The `snapshotTimestamp` field is an ISO-8601 instant set when the snapshot is created. It is used by `GET /api/fallback-odds-timestamp` to display the snapshot age in the UI. This approach works reliably in all environments (including packaged JARs) because the timestamp is read from the JSON content via `ClassPathResource.getInputStream()`, rather than depending on filesystem `lastModified()` which returns 0 for classpath entries nested inside a JAR.

#### Creating the Snapshot

When running locally (localhost, where Betfair works), an admin can capture fresh odds:

**Endpoint:** `GET /api/admin/snapshot-odds` (only available when `prod` profile is NOT active)

**What `snapshotOddsLocally()` does:**
1. Authenticates with Betfair (or uses cached session)
2. Fetches the full market catalogue
3. Fetches market books in batches of 40 (Betfair limit)
4. Combines catalogue + books into a single JSON object
5. Writes pretty-printed JSON to `src/main/resources/fallback-odds.json`
6. **Pushes the same JSON to the production server** via `POST https://fifa2026.scaffoldkit.dev/api/admin/odds/upload` (authenticated with a `Cookie: CF_Authorization=<jwt>` header using the `admin.cloudflare.jwt` property)

If the `ADMIN_CLOUDFLARE_JWT` environment variable is not set, the production push is silently skipped with an info-level log message.

> **Local development note:** The `ADMIN_CLOUDFLARE_JWT` value must be the `CF_Authorization` cookie copied from an active production browser session. The Cloudflare Edge expects the JWT as a cookie (`Cookie: CF_Authorization=<token>`), not as a custom `Cf-Access-Jwt-Assertion` header. To obtain a fresh token, open the production site in your browser (authenticating through Cloudflare Zero Trust), then copy the `CF_Authorization` cookie value from your browser's developer tools into your `.env` file as `ADMIN_CLOUDFLARE_JWT`. Note that these tokens expire, so you may need to refresh the cookie periodically.

The file is then committed to Git and deployed with the application.

#### File Location & Read Order

The fallback odds file is resolved in this order:

1. **Filesystem** — `{app.data.dir}/fallback-odds.json` (primary — writable at runtime via admin upload)
2. **Classpath** — `classpath:fallback-odds.json` (bundled in JAR — used only for fresh volumes)

#### Using the Fallback

When `simulateGroupStageOdds()` is called:

```
Attempt live Betfair API call
        │
        ├── Success → use live data
        │
        └── Failure (network error, BETTING_RESTRICTED_LOCATION, etc.)
                │
                ▼
        Load fallback-odds.json
                │
                ├── 1. Try filesystem ({app.data.dir}/fallback-odds.json)
                │      └── Success → use fallback data
                │
                ├── 2. Try classpath (bundled in JAR)
                │      └── Success → use fallback data
                │
                └── Failure → use equal 33.3% probability for all matches
```

The fallback path is handled by `BetfairIntegrationService.readFallbackOddsJson()`, which tries the filesystem first, then the classpath resource. The simulation method logs whether it's using live or fallback data.

#### Admin Upload Endpoint

**Endpoint:** `POST /api/admin/odds/upload` (admin only)

Allows an admin user to update the fallback odds file at runtime by POSTing raw Betfair JSON as the request body:

1. Checks that the user's email matches the admin email (same security check as Lock Score)
2. Validates the request body is valid JSON
3. Writes the JSON to `{app.data.dir}/fallback-odds.json` (creating parent directories if needed)
4. Emits an `INFO` event confirming the update

This enables updating odds in production without redeploying.

### 7.9 Group Stage Simulation via Odds

The `simulateGroupStageOdds()` method uses Betfair odds (live or fallback) to probabilistically generate scores for all 72 group matches.

**Algorithm per match:**

1. **Match Betfair markets to group matches** — Uses `BetfairNamesToCodes` to map Betfair runner names to FIFA team codes, then matches to internal group matches via a sorted team-pair key. **Home/Away is determined by the Betfair `sortPriority` field** (not array index or name splitting):
   - `sortPriority: 1` → Home team
   - `sortPriority: 2` → Away team
   - `sortPriority: 3` → Draw
2. **Extract best back prices** — For each runner (Home, Away, Draw), takes the best available back price using the `sortPriority` field from the market catalogue (mapped to book runners via `selectionId`).
3. **Align odds to GroupMatch order** — Since market-to-match matching uses a sorted team-pair (order-independent), the system checks whether Betfair's Home (`sortPriority 1`) corresponds to the `GroupMatch`'s `team1` or `team2`, and swaps odds if necessary to ensure correct alignment.
4. **Convert to probabilities** — Decimal odds → implied probability (`1/odds`), then normalise so all three sum to 1.0.
5. **Roll a random outcome** — Random double determines Team A win / Draw / Team B win.
6. **Pick a realistic scoreline** — Selected from weighted arrays of common football scores:
   - Team A wins: `{1,0}`, `{2,0}`, `{2,1}`, `{3,0}`, `{3,1}`, etc.
   - Draws: `{0,0}`, `{1,1}`, `{2,2}`
   - Team B wins: `{0,1}`, `{0,2}`, `{1,2}`, `{0,3}`, `{1,3}`, etc.
7. **Fallback for unmatched matches** — If no odds available, uses equal 33.3% probability per outcome.

**Batching:** Market books are fetched in batches of 40 (Betfair API limit per call).

**REST endpoint:** `POST /api/betfair/simulate-groups`

### 7.10 Configuration — `BetfairProperties`

**Profile:** `!prod` (disabled in production — the bean is not created when `prod` is active)

A `@ConfigurationProperties(prefix = "betfair")` record with validated fields:

| Field | Env Variable | Description |
|-------|-------------|-------------|
| `apiKey` | `BETFAIR_API_KEY` | Betfair application key |
| `username` | `BETFAIR_USERNAME` | Betfair account username |
| `password` | `BETFAIR_PASSWORD` | Betfair account password |
| `certPath` | `BETFAIR_CERT_PATH` | Root directory containing `ssl/` folder |

---

## 8. Error Notification System

### 8.1 Overview

The application provides a user-friendly notification system that surfaces backend errors, warnings, and informational messages in the UI. This ensures users are aware of issues (e.g., Betfair connection failures, persistence errors) without seeing technical stack traces.

### 8.2 Backend — `AppEventService`

A singleton `@Service` that maintains an in-memory list of recent events (capped at 20). Events are emitted by backend services and the global exception handler.

**Event types:** `INFO`, `WARNING`, `ERROR`

**Key methods:**
- `emitInfo(category, message)` — informational (blue) notification
- `emitWarning(category, message)` — warning (amber) notification
- `emitError(category, message)` — error (red) notification
- `getEvents()` — returns all stored events (oldest first)

**Categories:** `Betfair`, `System`, or any custom label.

**Betfair fallback handling:** When Betfair API is unreachable and the system falls back to saved odds, it emits an `INFO` event (not an error), since this is expected behavior in production. Only genuine failures (e.g., fallback file also missing) are emitted as errors.

### 8.3 Backend — `GlobalExceptionHandler`

A `@RestControllerAdvice` that catches unhandled exceptions from REST controllers:
- `Exception` → logs full stack trace, records `ERROR` event, returns HTTP 500 with short message
- `IllegalArgumentException` → logs warning, records `WARNING` event, returns HTTP 400 with short message

Messages are truncated to 150 characters with no stack traces in responses.

### 8.4 Frontend — Notification Toasts

The frontend polls `GET /api/events` every 10 seconds, compares timestamps to find new events, and displays them as animated toast notifications in the top-right corner.

**Notification styling:**
- **Info** (blue border): `ℹ️` icon, informational messages like Betfair fallback usage
- **Warning** (amber border): `⚠️` icon, degraded functionality
- **Error** (red border): `❌` icon, failures requiring attention

**Behavior:**
- Auto-dismiss after 8 seconds
- Manual dismiss via × button
- Max 5 visible at once (oldest dismissed first)
- Slide-in animation from the right
- First poll is silent (baseline), subsequent polls show only new events

### 8.5 REST Endpoint

`GET /api/events` returns:
```json
{
  "events": [
    {
      "timestamp": "2026-06-11T03:43:47.123456789Z",
      "type": "INFO",
      "category": "Betfair",
      "message": "Using saved odds snapshot (live Betfair data unavailable)."
    }
  ]
}
```

---

## 9. Frontend

A vanilla HTML/JS/CSS single-page application with no framework dependencies.

**Structure (`index.html`):**
- Header with "Seed Bracket from Groups", "Reset All", and "🎲 Simulate Group Stage via Betfair Odds" buttons (the latter showing the fallback-odds snapshot timestamp inside the button)
- User email display and auto-save status indicator
- Notification container (fixed, top-right) for error/warning/info toasts
- Tab bar switching between **Group Stage** and **Knockout Bracket** views
- Group Stage view: grid of 12 group cards, each showing standings table and 6 editable match score inputs
- Knockout Bracket view: horizontally-scrollable bracket visualization

**`app.js`:**
- **Static match schedule:** A hardcoded `STATIC_MATCH_SCHEDULE` dictionary maps all 72 group match IDs (A1–L6) to ISO-8601 date strings. This decouples match scheduling from the backend API, ensuring reliable chronological ordering even when Betfair markets close and dates disappear for past games.
- **Match processing pipeline:** The `processGroupMatches()` function transforms raw backend matches by (1) injecting static dates from `STATIC_MATCH_SCHEDULE`, (2) merging admin-locked scores from `lockedMatches`, and (3) sorting chronologically. The processed array is used for all group stage rendering.
- Loads team data from `/api/teams` and `/api/groups` on startup
- Renders group cards with inline score editors (number inputs)
- On score change → `POST /api/groups/{matchId}/score` → refreshes standings
- **Auto-save:** Collects all scores from the DOM, POSTs to `/api/user/state` via a 500ms debounced function
- **State restoration:** On startup, loads user metadata (isAdmin, lockedMatches) from `GET /api/user/state` **before** the first render via `loadUserMeta()`, then after initial rendering calls `restoreSavedState()` (reusing the cached response) to replay any user scores missing from the backend. This ensures lock icons, disabled inputs, and admin controls are visible immediately on page load without requiring a simulation click.
- Bracket tab calls `POST /api/bracket/seed` then `GET /api/bracket`
- Knockout score changes → `POST /api/bracket/{matchId}/score`
- Champion displayed when the Final has a result
- Betfair simulation → `POST /api/betfair/simulate-groups` with UI feedback
- **Notification polling:** Every 10s, fetches `GET /api/events` and displays new events as toast notifications
- **Admin lock/unlock:** On startup, stores `isAdmin` and `lockedMatches` from `/api/user/state`. Admin users see 🔒/🔓 buttons per match to lock/unlock actual results. Locked matches show disabled inputs with green border styling.

---

## 10. Configuration & Environment

### `application.properties`
```properties
spring.application.name=fifa

# H2 Database (file-based persistence)
spring.datasource.url=jdbc:h2:file:./data/fifa
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.h2.console.enabled=true

# Betfair Integration (defaults to empty — not needed in prod)
betfair.api-key=${BETFAIR_API_KEY:}
betfair.username=${BETFAIR_USERNAME:}
betfair.password=${BETFAIR_PASSWORD:}
betfair.cert-path=${BETFAIR_CERT_PATH:}

# Cloudflare Zero Trust (JWT validation)
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://scaffoldkit.cloudflareaccess.com/cdn-cgi/access/certs

# Application Data Directory (for actual-results.json)
app.data.dir=./data

# Logging
logging.level.dev.scaffoldkit.fifa.betfair=INFO
```

### `.env` file (loaded by `spring-dotenv`)
```
BETFAIR_API_KEY=your-api-key
BETFAIR_USERNAME=your-username
BETFAIR_PASSWORD=your-password
BETFAIR_CERT_PATH=/path/to/project/root
```

### `.env.example`
```
BETFAIR_CERT_PATH=
BETFAIR_API_KEY=
BETFAIR_USERNAME=
BETFAIR_PASSWORD=
```

### SSL Certificates
Generate Betfair client certificates and place them in `ssl/`:
```
ssl/client-2048.crt   # Client certificate
ssl/client-2048.key   # Private key (PKCS#1 or PKCS#8)
```
See `BETFAIR.md` for full setup instructions.

---

## 11. Data Flow — End to End

```
User sets group score in browser
        │
        ▼
POST /api/groups/{matchId}/score
        │
        ▼
TournamentController
        │
        ▼
GroupStageService.setGroupMatchScore()
  ├── Updates the match scores
  └── Recalculates all standings (points, GD, GF)
        │
        ▼
Returns advancement info (winners, runners-up, best 3rd)
        │
        ▼
Frontend calls debounced auto-save → POST /api/user/state

User clicks "Seed Bracket"
        │
        ▼
POST /api/bracket/seed
        │
        ▼
BracketService.seedBracket()
  ├── Gets group winners, runners-up, best 3rd-place groups
  ├── ThirdPlaceMatrixService.lookup(eliminatedGroups)
  │     └── Determines which 3rd-place teams face which group winners
  └── Populates all 16 R32 matches

User sets knockout score
        │
        ▼
POST /api/bracket/{matchId}/score
        │
        ▼
BracketService.setKnockoutScore()
  ├── Clears forward chain (removes old winner from subsequent matches)
  ├── Sets new score
  └── Propagates winner to next match slot

User clicks "Simulate via Betfair Odds"
        │
        ▼
POST /api/betfair/simulate-groups
        │
        ▼
BetfairIntegrationService.simulateGroupStageOdds()
  ├── Authenticates (or uses cached session)
  ├── Fetches market catalogue (live or fallback-odds.json)
  ├── Matches markets to group matches via BetfairNamesToCodes
  ├── Fetches market books (batched)
  ├── For each match: decimal odds → probability → roll → scoreline
  └── Returns map of matchId → [score1, score2]
        │
        ▼
TournamentController applies all scores via GroupStageService
```

---

## 12. Betfair Integration — Data Flow

```
Application Startup
        │
        ▼
BetfairIntegrationService.@PostConstruct init()
        │
        ├── Step 1: BetfairAuthClient.login()
        │     │
        │     │  mTLS POST → identitysso-cert.betfair.com/api/certlogin
        │     │  (uses betfairAuthRestTemplate with client cert)
        │     │
        │     └── Returns sessionToken (cached)
        │
        ├── Step 2: BetfairMarketClient.listMarketCatalogue(sessionToken)
        │     │
        │     │  POST → api.betfair.com/.../listMarketCatalogue/
        │     │  Filter: Soccer (eventType 1), MATCH_ODDS, Competition 12469077
        │     │
        │     └── Returns JSON array of markets (up to 100)
        │
        └── Step 3: BetfairMarketClient.listMarketBook(sessionToken, marketIds)
              │
              │  POST → api.betfair.com/.../listMarketBook/
              │  Price projection: EX_BEST_OFFERS (depth 3)
              │
              └── Returns JSON with back/lay prices per runner

If any step fails → app continues without live odds (logged as warning)
```

---

## 13. Betfair Fallback — Data Flow

```
POST /api/betfair/simulate-groups (or snapshot-odds)
        │
        ▼
BetfairIntegrationService
        │
        ├── Attempt live Betfair API call
        │     │
        │     ├── Success → Use live market data
        │     │
        │     └── Failure (network error, IP blocked, etc.)
        │           │
        │           ▼
        │           Load fallback-odds.json
        │           │
        │           ├── 1. Try filesystem ({app.data.dir}/fallback-odds.json)
        │           │      └── Success → Parse catalogue + books, use as data source
        │           │
        │           ├── 2. Try classpath (bundled in JAR)
        │           │      └── Success → Parse catalogue + books, use as data source
        │           │
        │           └── Failure → Equal 33.3% probability for all matches
        │
        ▼
  For each matched market:
        │
        ├── Map runner names → FIFA codes (BetfairNamesToCodes)
        │
        ├── Determine Home/Away via sortPriority field:
        │     sortPriority 1 → Home, sortPriority 2 → Away, sortPriority 3 → Draw
        │
        ├── Extract best back prices mapped by selectionId to sortPriority
        │
        ├── Align odds to GroupMatch team1/team2 order
        │     (swap Home/Away odds if Betfair Home ≠ GroupMatch team1)
        │
        ├── Convert: decimal odds → implied probability → normalise
        │
        ├── Random roll → pick outcome (T1 win / Draw / T2 win)
        │
        └── Pick realistic scoreline from weighted array

  Unmatched matches → equal 33.3% fallback
```

---

## 14. Deployment — Docker & Railway

### Dockerfile (Multi-Stage Build)

| Stage | Base Image | Purpose |
|-------|-----------|---------|
| Builder | `eclipse-temurin:21-jdk` | Compiles the Spring Boot JAR via Gradle |
| Runner | `eclipse-temurin:21-jre` | Runs the compiled JAR |

**Certificate handling:** Not needed in production. Since the live Betfair API is disabled via `@Profile("!prod")`, the Docker container does not decode SSL certificates. The `BETFAIR_CERT_B64` and `BETFAIR_KEY_B64` environment variables are no longer required on Railway.app. (They are still needed for local development if testing the live Betfair connection.)

### Railway.app Configuration

| Setting | Value |
|---------|-------|
| Persistent volume | Mounted at `/app/data` |
| Datasource URL override | `jdbc:h2:file:/app/data/predictions` |
| Spring profile | `prod` (set via `ENV SPRING_PROFILES_ACTIVE=prod` in Dockerfile; activates Cloudflare JWT validation) |
| Domain | Auto-generated via Railway (e.g., `fifa-production.up.railway.app`) |

**Environment variables (Railway.app):** `SPRING_DATASOURCE_URL`. The Betfair-related variables (`BETFAIR_API_KEY`, `BETFAIR_USERNAME`, `BETFAIR_PASSWORD`, `BETFAIR_CERT_B64`, `BETFAIR_KEY_B64`) are **no longer required** in production since the live Betfair API is permanently blocked from Railway.app.

> **Note:** The `prod` Spring profile is set by default in the Dockerfile via `ENV SPRING_PROFILES_ACTIVE=prod`. This ensures the Cloudflare Zero Trust JWT validation (`ProdSecurityConfig`) is always active in the Docker container. It can still be overridden at runtime by setting the `SPRING_PROFILES_ACTIVE` environment variable on the hosting platform.

---

## 15. Key Design Decisions

1. **Betfair integration is isolated** — The entire `betfair` package runs independently from the tournament engine. If Betfair credentials are missing or the API is unreachable, the tournament predictor works fully without it. In the `prod` profile, live Betfair API beans are not even loaded (`@Profile("!prod")`), eliminating all connection attempts and associated errors.

2. **Package-private encapsulation** — All Betfair classes except `BetfairIntegrationService` are package-private, keeping the integration's internals hidden from the rest of the application.

3. **Hybrid state model** — Tournament data (groups, matches, scores, bracket) lives in memory for fast interactive use. User-specific data (predictions) is persisted to an H2 file-based database so it survives server restarts. The tournament engine remains intentionally stateless and resettable.

4. **Bracket wiring** — Each `KnockoutMatch` knows its `nextMatchId` and `nextSlot` (1 or 2), forming a linked tree. When a score is set, the winner automatically propagates forward. Changing a score clears the entire downstream chain.

5. **Third-place matrix algorithm** — Uses a rotation-based derangement to assign 3rd-place teams to group winners, guaranteeing no group winner faces its own group's 3rd-place team. Falls back to a greedy assignment if no rotation works (unlikely with 8 positions).

6. **Vanilla frontend** — No React/Vue/Angular. The frontend is simple enough that a framework would add complexity without benefit. All state is server-side; the frontend is a thin rendering layer over the REST API.

7. **Profile-based security** — Spring Security with two configurations: `prod` profile validates Cloudflare Zero Trust JWTs, while the default profile injects a mock user. Both set `UserProfile` as the `@AuthenticationPrincipal`, keeping controller code uniform. The `UserProfileJwtAuthenticationConverter` auto-creates users on first JWT login.

8. **H2 file-based persistence** — H2 was chosen as an embedded database that requires no external server. The file-based mode (`jdbc:h2:file:./data/fifa`) ensures data survives restarts while keeping the development experience simple. The H2 web console (`/h2-console`) provides a convenient way to inspect data during development.

9. **Betfair fallback via file** — A snapshot mechanism allows odds to be captured on localhost (where Betfair works) and stored in the persistent data directory (`{app.data.dir}/fallback-odds.json`). The file can be updated at runtime via the admin upload endpoint (`POST /api/admin/odds/upload`) without redeploying. The system reads from the filesystem first, falling back to the classpath-bundled copy for fresh volumes. In production (where Betfair is IP-blocked), the system seamlessly falls back to the snapshot. The `prod` profile goes further by completely disabling live Betfair beans, so no connection is even attempted.

10. **Auto-save with debouncing** — The frontend auto-saves the full tournament state after every score change (debounced by 500ms), so users don't lose work. On page load, the saved state is compared to the backend state and replayed if the backend is fresh (e.g., after a server restart).

---

## 16. Known Implementation Notes

> **These are observations about the current codebase, documented as-is. No changes have been made.**

1. **`@Profile` on controller method** — `TournamentController.snapshotOdds()` uses `@Profile("!prod")` on an individual handler method. In Spring, `@Profile` is a component-level annotation and typically does not work on individual `@GetMapping` methods. This means the `/api/admin/snapshot-odds` endpoint may be accessible in all profiles, including production.

2. **`BETFAIR.md` says POST but code uses GET** — The documentation file `BETFAIR.md` instructs users to send a `POST` request to `/api/admin/snapshot-odds`, but the actual controller method is annotated with `@GetMapping`. The endpoint currently only responds to GET requests.

3. **Mock user profile loaded from DB** — `LocalSecurityConfig.MockAuthenticationFilter` loads the user's persisted `UserProfile` from the H2 database via `UserProfileRepository.findByEmail()`, falling back to a transient entity for first-time users. `UserStateController.saveState()` still handles the lookup-before-save pattern for safety, ensuring JPA performs an UPDATE rather than an INSERT on subsequent saves.

4. **Hardcoded competition ID** — `BetfairMarketClient` hardcodes the FIFA World Cup competition ID (`12469077`) in the market catalogue filter. If Betfair changes this ID between tournaments, the filter would need to be updated in source code.

5. **`BetfairNamesToCodes` name discrepancy** — The mapping uses `"Cape Verde"` (Betfair's spelling) while `GroupStageService` uses `"Cabo Verde"` (FIFA's official spelling). Both map to the same FIFA code `CPV`, so the integration works correctly, but the internal display name differs from the Betfair name.

6. **`DumpRunnerNamesRunner` marked temporary** — Source comments indicate this `CommandLineRunner` should be removed once the name mapping is stable. It remains in the codebase as a diagnostic tool.