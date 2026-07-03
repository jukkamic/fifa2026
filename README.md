# FIFA 2026 World Cup Predictor

A Spring Boot web app that lets users simulate the 2026 World Cup: play through the
48-team group stage, auto-advance qualifiers, run a 32-team knockout bracket, and
optionally pull live odds from the Betfair Exchange. Each user's predictions are
persisted to an H2 database.

For the big picture — bounded contexts, data flow, and where to look when debugging —
read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). This README focuses on getting
the app running and doing day-to-day work.

## Prerequisites

- **Java 21** (JDK)
- **Git Bash** (on Windows) or any POSIX shell
- Optional, only for live Betfair odds: a Betfair account + API key + client certificate
  (see [Betfair Integration](#betfair-integration-optional) below)

## Quick Start (Local)

1. **Clone and enter the repo.**

   ```bash
   git clone https://github.com/jukkamic/fifa2026.git fifa
   cd fifa
   ```

2. **Create your `.env` file** from the template. Betfair values can be left blank —
   the app runs fully without them (it just won't show live odds).

   ```bash
   cp .env.example .env
   # then edit .env if you want live Betfair odds
   ```

3. **Run the app.**

   ```bash
   ./gradlew bootRun
   ```

4. **Open it.** The app runs on <http://localhost:8080>.
   - The H2 console is at <http://localhost:8080/h2-console> (JDBC URL `jdbc:h2:file:./data/fifa`, user `sa`, no password).

### Local Development Tips

**Reload static resources without rebooting.** Point Spring at the source `static/`
directory so frontend edits (HTML/JS/CSS) take effect on refresh without restarting
the server:

```bash
./gradlew bootRun --args="--spring.web.resources.static-locations=file:src/main/resources/static/"
```

**DevTools is included.** Java source changes trigger an automatic restart when running
via `bootRun` (the `spring-boot-devtools` dependency is active in dev).

## Running Tests

```bash
./gradlew test
```

Run a single test class (e.g. the Betfair connection check):

```bash
./gradlew test --tests "dev.scaffoldkit.fifa.betfair.BetfairConnectionTest"
```

## Betfair Integration

The Betfair integration is what keeps production odds fresh. Because Betfair blocks the
production server's IP (see [Why odds are refreshed locally](#why-odds-are-refreshed-locally)),
odds are fetched **locally** and pushed to production on a schedule.

You can run the app without any Betfair credentials — it will simply use the committed
`fallback-odds.json` snapshot and show no live data. But to **refresh odds** (locally or
to feed production), you need a full set of credentials.

Full certificate and API-key setup is in [`BETFAIR.md`](BETFAIR.md). In short:

1. Generate a client cert/key in `ssl/` (see BETFAIR.md) and upload the `.crt` to Betfair.
2. Fill in your `.env`:

   ```
   BETFAIR_CERT_PATH=/absolute/path/to/project/root
   BETFAIR_API_KEY=your_delayed_app_key
   BETFAIR_USERNAME=your_username
   BETFAIR_PASSWORD=your_password
   ADMIN_CLOUDFLARE_JWT=           # optional — only to push snapshots to production
   ```

3. Start the app; it authenticates on boot and (outside `prod`) snapshots odds every 13 minutes.

## Admin & Diagnostic Tools

### Capture a one-off odds snapshot (local)

Hit the admin endpoint from a machine where Betfair is reachable. It fetches live odds
and writes `src/main/resources/fallback-odds.json`, then optionally pushes the same data
to production (if `ADMIN_CLOUDFLARE_JWT` is set):

```
GET http://localhost:8080/api/admin/snapshot-odds
```

### Dump Betfair team names

Betfair uses its own spellings (e.g. `Austria (W)`). This fetches every runner name from
World Cup markets and writes them to `betfair-runner-names.txt`, useful for refreshing
the name-to-code mapping:

```bash
./gradlew bootRun --args="--betfair.dump-runner-names=true"
```

The app writes the file, prints results, and exits.

### Standalone odds updater

A runnable fat JAR that refreshes Betfair odds and uploads them to the web service
**without starting the Spring Boot web server** — handy for cron jobs or a dedicated
machine. It mirrors the in-app `BetfairOddsSnapshotScheduler`.

**Build:**

```bash
./gradlew standaloneJar --no-daemon --no-configuration-cache
```

Produces `build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar`.

**Environment:** same `BETFAIR_*` variables as the web app (read from real environment
variables, not `.env`). To load `.env` into the current PowerShell session first:

```powershell
Get-Content .env | ForEach-Object { if ($_ -match '^\s*([^#=]+)=(.*)$') { [System.Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process') } }
```

**Run:**

```bash
java -jar build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar --help          # show all options
java -jar build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar                 # single snapshot, then exit
java -jar build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar --loop --interval 13   # continuous, every 13 min
java -jar build/libs/fifa-0.0.1-SNAPSHOT-standalone.jar --output ./data/fallback-odds.json --no-push  # custom path, skip prod push
```

## Production Deployment (Railway.app)

The repo includes a multi-stage `Dockerfile` (builds with JDK 21, runs on JRE 21) that
Railway detects automatically. The container starts with the `prod` Spring profile active,
which enables Cloudflare Zero Trust JWT validation and disables the live Betfair API.

1. **Create the project.** On <https://railway.app>, choose **New Project → Deploy from
   GitHub repo** and select this repository. Railway auto-detects the Dockerfile.

2. **Attach a persistent volume** so user predictions (the H2 database) survive restarts.
   In your service → **Volumes → Create Volume**, mounted at `/app/data`.

3. **Set environment variables.** In the **Variables** tab:

   | Variable | Value |
   |----------|-------|
   | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:/app/data/predictions` |

   That's the only one the production server needs. Live Betfair API credentials and
   SSL certificates are deliberately **not** set in production — see
   [Why odds are refreshed locally](#why-odds-are-refreshed-locally) below.

4. **Generate a domain.** In **Settings → Networking → Generate Domain** to get a public
   URL (e.g. `fifa-production.up.railway.app`).

### Why odds are refreshed locally

Betfair blocks connections from Railway's IPs (likely detecting it as a cloud host),
returning `BETTING_RESTRICTED_LOCATION`. So the production server **cannot fetch live
odds itself**. The workaround runs the opposite direction from a normal setup:

- The **production** app runs with the `prod` profile, which disables the live Betfair
  beans entirely — no credentials, no SSL cert, no connection attempts. It simply serves
  whatever odds are in the committed `fallback-odds.json`.
- **Locally** (on a residential IP that Betfair allows), the app fetches live odds and
  automatically **pushes them to production** via the `POST /api/admin/odds/upload`
  endpoint, authenticated with an admin Cloudflare JWT.

This push happens automatically while you run the app locally: the
`BetfairOddsSnapshotScheduler` runs every 13 minutes, and a single
`GET /api/admin/snapshot-odds` triggers one immediately. So the operational model is:
**keep a local instance running on a non-blocked IP to keep production odds fresh.** The
[standalone odds updater](#standalone-odds-updater) does the same job without booting
the web server.

To enable the production push, set `ADMIN_CLOUDFLARE_JWT` in your local `.env` (copy the
`CF_Authorization` cookie value from an authenticated production browser session — it
expires periodically and must be refreshed). If unset, the push is skipped and the local
snapshot still writes `fallback-odds.json` in the repo.
