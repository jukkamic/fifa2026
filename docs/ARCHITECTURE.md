# FIFA 2026 World Cup — Project Architecture & API Reference

> **Audience:** This document is written for both **humans** and **LLMs** to quickly understand the project's purpose, structure, classes, and integrations.

---

## 1. What Is This Project?

A **Spring Boot 3.4 web application** that serves as an interactive **FIFA 2026 World Cup tournament predictor**. It lets users:

- **Simulate the group stage** — 12 groups (A–L) of 4 teams playing round-robin (72 group matches total).
- **Predict advancement** — Automatically computes which 24 teams advance (12 group winners + 12 runners-up + 8 best third-place teams) using FIFA's official tie-breaking rules and the **Annex C third-place matrix**.
- **Play through the knockout bracket** — A 32-team single-elimination bracket (R32 → R16 → QF → SF → Final), seeded from group results, with automatic winner propagation.
- **Speculate on paths to the final** — Users set hypothetical scores and watch how the bracket reshapes, exploring different tournament scenarios.
- *(In progress)* **View live Betfair odds** — An integration with the Betfair Exchange API fetches real-world betting market data for tournament matches.

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
| SSL/TLS | Bouncy Castle (`bcpkix-jdk18on:1.80`) for PEM key parsing |
| Env Config | `spring-dotenv:4.0.0` — loads `.env` into Spring environment |
| Frontend | Vanilla HTML + CSS + JavaScript (no framework) |
| Testing | JUnit 5 + Spring Boot Test |

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
│   └── BetfairSslConfig.java            # Builds mTLS RestTemplate beans from PEM files
├── controller/
│   └── TournamentController.java        # REST API (/api/*)
├── model/
│   ├── Team.java                        # Immutable team value object (code, name, group, flag)
│   ├── GroupMatch.java                  # Group-stage match with mutable scores
│   ├── GroupStanding.java               # Tracks W/D/L, GF/GA, GD, points; implements Comparable
│   ├── KnockoutMatch.java               # Knockout match with next-match wiring
│   └── UserProfile.java                # JPA entity: user predictions persisted to H2
├── repository/
│   └── UserProfileRepository.java       # Spring Data JPA repository for UserProfile
├── service/
│   ├── GroupStageService.java           # 12 groups × 4 teams, standings, advancement
│   ├── BracketService.java              # 32-team knockout bracket (left/right halves)
│   └── ThirdPlaceMatrixService.java     # FIFA Annex C 3rd-place assignment algorithm
└── web/
    ├── UserContext.java                 # ThreadLocal holder for current request's UserProfile
    └── LocalhostUserFilter.java         # Simulates logged-in user on localhost (hardcoded email)

src/main/resources/
├── application.properties               # Spring config (env var references)
└── static/
    ├── index.html                       # Single-page app shell
    ├── app.js                           # Vanilla JS frontend logic
    └── style.css                        # Styling

ssl/
├── openssl.cnf                          # OpenSSL config for generating Betfair client certs
├── client-2048.crt                      # Client certificate (not committed)
└── client-2048.key                      # Private key (not committed)

docs/
└── ARCHITECTURE.md                      # ← This file
```

---

## 4. Tournament Engine

### 4.1 `GroupStageService`

**Purpose:** Manages the 12 groups (A–L), each containing 4 teams playing round-robin (6 matches per group, 72 total). Tracks standings, computes group rankings, and determines which teams advance.

| Aspect | Detail |
|--------|--------|
| Teams | 48 teams hardcoded (FIFA codes like `BRA`, `ENG`, `MEX`) |
| Groups | `A`–`L`, each with exactly 4 teams |
| Matches | 6 per group, IDs like `A1`, `A2`, … `L6` |
| Standings sorting | Points desc → Goal Difference desc → Goals For desc |
| Advancement | Top 2 per group (24 teams) + 8 best 3rd-place teams = 32 |

**Key Methods:**
- `setGroupMatchScore(matchId, score1, score2)` — sets a score and recalculates all standings
- `getSortedStandings(group)` — returns standings ranked best-first
- `getAllGroupWinners()` / `getAllRunnersUp()` / `getAllThirdPlaces()` — maps of group → team code
- `getBestThirdPlaceGroups()` — the 8 groups whose 3rd-place teams advance
- `resetAll()` — clears all scores

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

All endpoints are under `/api`. The controller is `TournamentController`.

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

---

## 6. User Profiles & Persistence

### 6.1 Overview

The `model/`, `repository/`, and `web/` packages work together to provide user-specific prediction storage via a **file-based H2 database** with **Spring Data JPA**. Each user's tournament predictions are stored as a JSON string in the `user_profiles` table.

### 6.2 Database — H2 (File-Based)

| Aspect | Detail |
|--------|--------|
| Database | H2 embedded, persisted to `./data/fifa.*` files |
| DDL mode | `spring.jpa.hibernate.ddl-auto=update` (auto-creates/updates tables) |
| Connection | `jdbc:h2:file:./data/fifa`, username `sa`, no password |
| Console | H2 web console enabled at `http://localhost:8080/h2-console` |
| Dialect | `org.hibernate.dialect.H2Dialect` |

### 6.3 JPA Entity — `UserProfile`

| Field | Type | Column | Constraints |
|-------|------|--------|-------------|
| `id` | `Long` | `id` | `@Id`, `@GeneratedValue(IDENTITY)` |
| `email` | `String` | `email` | `NOT NULL`, `UNIQUE` |
| `predictionsJson` | `String` | `predictions_json` | `@Lob`, `CLOB` |
| `updatedAt` | `Instant` | `updated_at` | — |

### 6.4 Repository — `UserProfileRepository`

A standard Spring Data `JpaRepository<UserProfile, Long>` with one derived query:

| Method | Returns | Purpose |
|--------|---------|---------|
| `findByEmail(String email)` | `Optional<UserProfile>` | Look up a user by email address |

All standard `JpaRepository` methods (`save`, `findById`, `findAll`, `delete`, etc.) are inherited.

### 6.5 Simulated Authentication — `LocalhostUserFilter`

**Purpose:** A `OncePerRequestFilter` (`@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`) that simulates an authenticated user during local development.

**Flow per request:**
```
Incoming HTTP request
        │
        ▼
LocalhostUserFilter.doFilterInternal()
        │
        ├── Lookup UserProfile by hardcoded email "testuser@example.com"
        │     └── If not found → create with blank predictions ("{}") and save
        │
        ├── Place UserProfile into UserContext (ThreadLocal)
        │
        ├── filterChain.doFilter()  ← rest of the request proceeds
        │
        └── finally: UserContext.clear()  ← ThreadLocal cleanup
```

**Class: `UserContext`**
A simple `ThreadLocal<UserProfile>` holder with static `set()`, `get()`, and `clear()` methods. Any controller or service can call `UserContext.get()` to access the current request's user.

> **Note:** This is a development-only authentication mechanism. For production, replace `LocalhostUserFilter` with a real auth filter (e.g., Spring Security with OAuth2 or JWT).

---

## 7. Betfair Exchange Integration

### 7.1 Overview

The `betfair` package integrates with the **Betfair Exchange API** to fetch live betting odds for soccer matches. This is intended to overlay real market prices onto the tournament predictor, helping users compare their speculative paths against what the betting market thinks.

The integration uses **mutual TLS (mTLS) certificate-based authentication** (Betfair's non-interactive login flow), which requires a client certificate and private key stored in the `ssl/` directory.

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
| `listMarketBook(sessionToken, marketIds)` | `/listMarketBook/` | Fetches current best back/lay prices |

**`listMarketCatalogue` details:**
- Filters by Soccer Event Type ID `1`
- Only `MATCH_ODDS` market type
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

### 7.4 Orchestration — `BetfairIntegrationService`

**Purpose:** Top-level `@Service` that orchestrates the full Betfair pipeline. The only public class in the `betfair` package.

**Startup Sequence** (`@PostConstruct`):
1. **Authenticate** — calls `authClient.login()`, caches the session token
2. **Fetch market catalogue** — gets all soccer MATCH_ODDS markets
3. **Fetch market book** — gets live odds for the first 5 markets
4. **Log results** — prints a human-readable summary to the console

If authentication fails, the app continues without live odds (graceful degradation).

**Public API:**
- `authenticate()` → `boolean` — performs login and caches session token
- `fetchMarketCatalogue()` → `String` (raw JSON) — discovers markets
- `fetchMarketBook(marketIds)` → `String` (raw JSON) — gets odds for specific markets
- `getSessionToken()` → `String` — returns cached token

### 7.5 SSL Configuration — `BetfairSslConfig`

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

### 7.6 Configuration — `BetfairProperties`

A `@ConfigurationProperties(prefix = "betfair")` record with validated fields:

| Field | Env Variable | Description |
|-------|-------------|-------------|
| `apiKey` | `BETFAIR_API_KEY` | Betfair application key |
| `username` | `BETFAIR_USERNAME` | Betfair account username |
| `password` | `BETFAIR_PASSWORD` | Betfair account password |
| `certPath` | `BETFAIR_CERT_PATH` | Root directory containing `ssl/` folder |

---

## 8. Frontend

A vanilla HTML/JS/CSS single-page application with no framework dependencies.

**Structure (`index.html`):**
- Header with "Seed Bracket from Groups" and "Reset All" buttons
- Tab bar switching between **Group Stage** and **Knockout Bracket** views
- Group Stage view: grid of 12 group cards, each showing standings table and 6 editable match score inputs
- Knockout Bracket view: horizontally-scrollable bracket visualization

**`app.js` (345 lines):**
- Loads team data from `/api/teams` and `/api/groups` on startup
- Renders group cards with inline score editors (number inputs)
- On score change → `POST /api/groups/{matchId}/score` → refreshes standings
- Bracket tab calls `POST /api/bracket/seed` then `GET /api/bracket`
- Knockout score changes → `POST /api/bracket/{matchId}/score`
- Champion displayed when the Final has a result

---

## 9. Configuration & Environment

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

# Betfair Integration
betfair.api-key=${BETFAIR_API_KEY}
betfair.username=${BETFAIR_USERNAME}
betfair.password=${BETFAIR_PASSWORD}
betfair.cert-path=${BETFAIR_CERT_PATH}

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

### SSL Certificates
Generate Betfair client certificates and place them in `ssl/`:
```
ssl/client-2048.crt   # Client certificate
ssl/client-2048.key   # Private key (PKCS#1 or PKCS#8)
```
An `openssl.cnf` template is provided in the `ssl/` directory.

---

## 10. Data Flow — End to End

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

User clicks "Seed Bracket"
        │
        ▼
POST /api/bracket/seed
        │
        ▼
BracketService.seedBracket()
  ├── Gets group winners, runners-up, best 3rd-place groups
  ├── ThirdPlaceMatrixService.lookup(eliminatedGroups)
  │     └── Dertermines which 3rd-place teams face which group winners
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
```

---

## 11. Betfair Integration — Data Flow

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
        │     │  Filter: Soccer (eventType 1), MATCH_ODDS
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

## 12. Key Design Decisions

1. **Betfair integration is isolated** — The entire `betfair` package runs independently from the tournament engine. If Betfair credentials are missing or the API is unreachable, the tournament predictor works fully without it.

2. **Package-private encapsulation** — All Betfair classes except `BetfairIntegrationService` are package-private, keeping the integration's internals hidden from the rest of the application.

3. **Hybrid state model** — Tournament data (groups, matches, scores, bracket) lives in memory for fast interactive use. User-specific data (predictions) is persisted to an H2 file-based database so it survives server restarts. The tournament engine remains intentionally stateless and resettable.

4. **Bracket wiring** — Each `KnockoutMatch` knows its `nextMatchId` and `nextSlot` (1 or 2), forming a linked tree. When a score is set, the winner automatically propagates forward. Changing a score clears the entire downstream chain.

5. **Third-place matrix algorithm** — Uses a rotation-based derangement to assign 3rd-place teams to group winners, guaranteeing no group winner faces its own group's 3rd-place team. Falls back to a greedy assignment if no rotation works (unlikely with 8 positions).

6. **Vanilla frontend** — No React/Vue/Angular. The frontend is simple enough that a framework would add complexity without benefit. All state is server-side; the frontend is a thin rendering layer over the REST API.

7. **Simulated authentication for development** — `LocalhostUserFilter` uses a hardcoded email (`testuser@example.com`) to simulate a logged-in user on localhost. This avoids the complexity of setting up real authentication during development. The `UserContext` ThreadLocal pattern keeps the current user accessible anywhere in the request chain without passing objects through method signatures. For production, this filter should be replaced with a real auth mechanism (e.g., Spring Security with OAuth2 or JWT).

8. **H2 file-based persistence** — H2 was chosen as an embedded database that requires no external server. The file-based mode (`jdbc:h2:file:./data/fifa`) ensures data survives restarts while keeping the development experience simple — just run the app and the database is ready. The H2 web console (`/h2-console`) provides a convenient way to inspect data during development.
