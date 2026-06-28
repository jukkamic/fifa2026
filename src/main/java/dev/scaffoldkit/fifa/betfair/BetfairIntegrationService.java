package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scaffoldkit.fifa.betfair.model.BetfairEvent;
import dev.scaffoldkit.fifa.betfair.model.BetfairExchangePrices;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketCatalog;
import dev.scaffoldkit.fifa.betfair.model.BetfairOddsSnapshot;
import dev.scaffoldkit.fifa.betfair.model.BetfairRunnerBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairRunnerCatalog;
import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.model.KnockoutMatch;
import dev.scaffoldkit.fifa.service.AppEventService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Top-level orchestrator for the Betfair Exchange API integration.
 *
 * <p>
 * On application startup this service:
 * <ol>
 * <li>Authenticates with Betfair via mutual-TLS certlogin</li>
 * <li>Fetches the soccer market catalogue (Event Type 1 - MATCH_ODDS)</li>
 * <li>Fetches market book data (current best back/lay prices)</li>
 * <li>Logs key data at each step for verification</li>
 * </ol>
 *
 * <p>
 * In the {@code prod} profile, the Betfair API dependencies (auth client,
 * market client, SSL config) are not loaded, so this service operates in
 * <b>fallback-only mode</b> — all live API calls are skipped and data is read
 * from {@code fallback-odds.json} exclusively.
 *
 * <p>
 * This service is intentionally isolated from the primary tournament engine
 * and can be enabled/disabled via configuration.
 */
@Service
public class BetfairIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(BetfairIntegrationService.class);

    private final AppEventService appEvents;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Live API clients — absent in the prod profile (Betfair blocked on
     * Railway.app).
     */
    private final BetfairAuthClient authClient;
    private final BetfairMarketClient marketClient;

    /** Whether the live Betfair API is available (false in prod). */
    private final boolean liveApiAvailable;

    /**
     * Path to the filesystem copy of fallback-odds.json (inside the persistent data
     * directory).
     */
    private final Path fallbackOddsPath;

    /** Currently active session token (cached after login). */
    private volatile String sessionToken;

    /** Cloudflare Access JWT for pushing odds to the production server. */
    private final String cloudflareJwt;

    /** Production server URL for the odds upload endpoint. */
    private static final String PROD_ODDS_UPLOAD_URL = "https://fifa2026.scaffoldkit.dev/api/admin/odds/upload";

    public BetfairIntegrationService(
            ObjectProvider<BetfairAuthClient> authClientProvider,
            ObjectProvider<BetfairMarketClient> marketClientProvider,
            AppEventService appEvents,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.data.dir:./data}") String dataDir,
            @Value("${admin.cloudflare.jwt:}") String cloudflareJwt) {
        this.authClient = authClientProvider.getIfAvailable();
        this.marketClient = marketClientProvider.getIfAvailable();
        this.appEvents = appEvents;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.liveApiAvailable = this.authClient != null && this.marketClient != null;
        this.fallbackOddsPath = Paths.get(dataDir, "fallback-odds.json");
        this.cloudflareJwt = cloudflareJwt;
    }

    /**
     * Runs the full authentication + data-fetch pipeline on startup
     * so we can immediately verify the connection in logs.
     *
     * <p>
     * In the prod profile this is a no-op — the live Betfair API is
     * permanently blocked from Railway.app hosting.
     */
    @PostConstruct
    void init() {
        if (!liveApiAvailable) {
            log.info("===========================================================");
            log.info("  Betfair Integration Service - PRODUCTION MODE");
            log.info("  Live Betfair API is disabled (blocked on Railway.app).");
            log.info("  All odds features will use fallback-odds.json.");
            log.info("===========================================================");
            return;
        }

        log.info("===========================================================");
        log.info("  Betfair Integration Service - initialising...");
        log.info("===========================================================");

        try {
            authenticate();
            if (sessionToken != null) {
                fetchAndLogMarketCatalogue();
                appEvents.emitInfo("Betfair",
                        "Successfully connected to Betfair Exchange API.");
            }
        } catch (Exception e) {
            log.warn("Betfair integration did not initialise - the app will continue " +
                    "without live odds. Reason: {}", e.getMessage());
            appEvents.emitInfo("Betfair",
                    "Betfair API is not available. Odds features will use saved data. " +
                            "This is normal in production environments.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Snapshots the Betfair odds data locally.
     */
    public void snapshotOddsLocally() throws Exception {
        if (!liveApiAvailable) {
            log.warn("Cannot snapshot odds: Betfair live API is not available in this environment.");
            appEvents.emitWarning("Betfair",
                    "Cannot create odds snapshot in production. Run locally to capture odds.");
            throw new IllegalStateException(
                    "Betfair live API is not available in this environment. Run locally to capture odds.");
        }

        log.info("Starting local snapshot of Betfair odds...");
        if (sessionToken == null) {
            authenticate();
        }
        if (sessionToken == null) {
            log.error("Failed to authenticate for snapshot.");
            appEvents.emitWarning("Betfair",
                    "Cannot create odds snapshot: Betfair authentication failed.");
            throw new IllegalStateException("Cannot create odds snapshot: Betfair authentication failed.");
        }

        try {
            List<BetfairMarketCatalog> catalogue = marketClient.listMarketCatalogue(sessionToken);
            if (catalogue.isEmpty()) {
                log.error("Failed to fetch market catalogue for snapshot.");
                throw new IllegalStateException("Failed to fetch market catalogue for snapshot.");
            }

            List<String> matchedMarketIds = new ArrayList<>();
            for (BetfairMarketCatalog market : catalogue) {
                matchedMarketIds.add(market.marketId());
            }

            // Fetch market books in batches (Betfair limit: 40 per call)
            List<BetfairMarketBook> books = new ArrayList<>();
            int batchSize = 40;
            for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                List<String> batch = matchedMarketIds.subList(i, Math.min(i + batchSize, matchedMarketIds.size()));
                books.addAll(marketClient.listMarketBook(sessionToken, batch));
            }

            BetfairOddsSnapshot snapshot = new BetfairOddsSnapshot(
                    catalogue, books, Instant.now().toString());

            String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);

            // ── Save to local file ────────────────────────────────────────
            Path path = Paths.get("src/main/resources/fallback-odds.json");
            Files.writeString(path, jsonPayload,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Successfully saved snapshot to {}", path.toAbsolutePath());

            // ── Push to production server ─────────────────────────────────
            pushOddsToProduction(jsonPayload);

            // Notify listeners (e.g. TournamentController) that odds data changed
            // so any in-memory caches are invalidated.
            eventPublisher.publishEvent(new OddsUpdatedEvent("Betfair odds snapshot"));

        } catch (Exception e) {
            log.error("Error during snapshot", e);
            throw e;
        }
    }

    /**
     * Pushes the raw odds JSON to the production server via the admin upload
     * endpoint. The request is authenticated with a Cloudflare Access JWT.
     * Only attempts the push if the JWT is configured (non-empty).
     */
    private void pushOddsToProduction(String jsonPayload) throws Exception {
        if (cloudflareJwt == null || cloudflareJwt.isBlank()) {
            log.info("Cloudflare JWT not configured — skipping push to production server. " +
                    "Set ADMIN_CLOUDFLARE_JWT to enable automatic odds upload.");
            return;
        }

        log.info("Pushing odds snapshot to production server ({})...", PROD_ODDS_UPLOAD_URL);
        RestClient restClient = RestClient.create();
        var response = restClient.post()
                .uri(PROD_ODDS_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "CF_Authorization=" + cloudflareJwt)
                .body(jsonPayload)
                .retrieve()
                .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("Successfully pushed odds to production server - HTTP {}",
                    response.getStatusCode().value());
        } else if (response.getStatusCode().value() == 302) {
            throw new Exception("Production server returned 302 redirect — the Cloudflare JWT " +
                    "(ADMIN_CLOUDFLARE_JWT) is likely expired or invalid. " +
                    "Generate a fresh token by copying the CF_Authorization cookie from your browser's developer tools.");
        } else {
            throw new Exception("Production server returned non-2xx status: HTTP " +
                    response.getStatusCode().value() + " — body: " + response.getBody());
        }
    }

    /**
     * Performs the certlogin authentication and caches the session token.
     *
     * @return {@code true} if authentication succeeded
     */
    public boolean authenticate() {
        if (!liveApiAvailable) {
            log.debug("authenticate() called but live Betfair API is not available — skipping.");
            return false;
        }

        log.info("Step 1: Authenticating with Betfair (mTLS certlogin)...");
        sessionToken = authClient.login();

        if (sessionToken != null) {
            log.info("Authentication successful - session token length: {}",
                    sessionToken.length());
            return true;
        }

        log.error("Authentication failed - check credentials and certificates");
        appEvents.emitWarning("Betfair",
                "Authentication with Betfair API failed. Check credentials and certificates.");
        return false;
    }

    /**
     * Fetches the soccer match-odds market catalogue.
     *
     * @return list of market catalogue entries, or empty list if unavailable
     */
    public List<BetfairMarketCatalog> fetchMarketCatalogue() {
        if (!liveApiAvailable)
            return List.of();
        ensureAuthenticated();
        return marketClient.listMarketCatalogue(sessionToken);
    }

    /**
     * Fetches market book (odds) for the given market IDs.
     *
     * @param marketIds the Betfair market IDs
     * @return list of market book entries, or empty list if unavailable
     */
    public List<BetfairMarketBook> fetchMarketBook(List<String> marketIds) {
        if (!liveApiAvailable)
            return List.of();
        ensureAuthenticated();
        return marketClient.listMarketBook(sessionToken, marketIds);
    }

    /**
     * Returns the current cached session token (may be {@code null} if not
     * yet authenticated).
     */
    public String getSessionToken() {
        return sessionToken;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void ensureAuthenticated() {
        if (sessionToken == null) {
            throw new IllegalStateException(
                    "Not authenticated - call authenticate() first");
        }
    }

    /**
     * Reads and deserializes the fallback-odds.json into a typed
     * {@link BetfairOddsSnapshot}, trying the persistent filesystem location
     * first (so the file can be updated without redeploy), then falling back
     * to the classpath resource (bundled in the JAR).
     *
     * @return the deserialized snapshot, or {@code null} if no source is available
     */
    private BetfairOddsSnapshot readFallbackOddsSnapshot() {
        // 1. Try filesystem (persistent volume in prod, local data dir in dev)
        if (Files.exists(fallbackOddsPath)) {
            try {
                String json = Files.readString(fallbackOddsPath, StandardCharsets.UTF_8);
                log.info("Read fallback-odds.json from filesystem: {}", fallbackOddsPath.toAbsolutePath());
                return objectMapper.readValue(json, BetfairOddsSnapshot.class);
            } catch (Exception e) {
                log.warn("Failed to read fallback-odds.json from filesystem ({}): {}",
                        fallbackOddsPath.toAbsolutePath(), e.getMessage());
            }
        }

        // 2. Fallback: classpath resource (bundled in JAR, immutable)
        try {
            ClassPathResource resource = new ClassPathResource("fallback-odds.json");
            try (var is = resource.getInputStream()) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Read fallback-odds.json from classpath (filesystem file not found at {})",
                        fallbackOddsPath.toAbsolutePath());
                return objectMapper.readValue(json, BetfairOddsSnapshot.class);
            }
        } catch (Exception e) {
            log.warn("Failed to read fallback-odds.json from classpath: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Returns the filesystem path where fallback-odds.json is stored.
     * Used by the admin upload endpoint to write new odds data.
     */
    public Path getFallbackOddsPath() {
        return fallbackOddsPath;
    }

    // ── Startup logging ──────────────────────────────────────────────────

    /**
     * Fetches the catalogue and logs a human-readable summary of discovered
     * markets, then fetches the market book for the first few and logs prices.
     */
    private void fetchAndLogMarketCatalogue() {
        log.info("Step 2: Fetching soccer market catalogue...");

        List<BetfairMarketCatalog> markets = marketClient.listMarketCatalogue(sessionToken);
        if (markets.isEmpty()) {
            log.error("Failed to fetch market catalogue");
            return;
        }

        int count = markets.size();
        log.info("Received {} soccer match-odds market(s)", count);

        List<String> marketIds = new ArrayList<>();
        int logged = 0;
        for (BetfairMarketCatalog market : markets) {
            String marketId = market.marketId();
            String marketName = market.marketName();
            BetfairEvent event = market.event();
            String startTime = event != null ? event.openDate() : "";
            String eventName = event != null ? event.name() : "";
            String marketURL = createMarketURL(market);

            marketIds.add(marketId);

            if (logged < 10) {
                log.info("  [{}/{}] {} | {} | {} | start={}",
                        logged + 1, count, marketId, eventName,
                        marketName, startTime);
                log.info(marketURL);
                logged++;
            }
        }

        // Fetch odds for first batch of markets
        if (!marketIds.isEmpty()) {
            fetchAndLogMarketBook(marketIds.subList(0, Math.min(5, marketIds.size())));
        }
    }

    /**
     * Builds the human-readable Betfair Exchange URL for a market.
     * <p>
     * Works identically in local and production environments because it only
     * reads from the market model (available from both the live API and
     * {@code fallback-odds.json}).
     */
    private String createMarketURL(BetfairMarketCatalog market) {
        BetfairEvent event = market.event();
        if (event == null) {
            return "";
        }
        String eventName = event.name();
        String eventId = event.id();

        // Betfair URL slug: "Mexico v South Africa" "Mexico-v-South-Africa"
        String eventSlug = eventName.replace(" ", "-").toLowerCase();

        return "https://www.betfair.com/exchange/plus/en/football/fifa-world-cup/"
                + eventSlug + "-betting-" + eventId;
    }

    private void fetchAndLogMarketBook(List<String> marketIds) {
        log.info("Step 3: Fetching market book for {} market(s)...", marketIds.size());

        List<BetfairMarketBook> books = marketClient.listMarketBook(sessionToken, marketIds);
        if (books.isEmpty()) {
            log.error("Failed to fetch market book");
            return;
        }

        log.info("Received market book data for {} market(s)", books.size());

        for (BetfairMarketBook book : books) {
            String marketId = book.marketId();
            boolean inPlay = book.inplay();
            List<BetfairRunnerBook> runners = book.runners();
            log.info("  Market {} | inPlay={} | {} runner(s)",
                    marketId, inPlay, runners.size());

            for (BetfairRunnerBook runner : runners) {
                long selectionId = runner.selectionId();

                // Best back price
                BetfairExchangePrices ex = runner.ex();
                String bestBack = "n/a";
                String bestLay = "n/a";

                if (ex != null) {
                    if (!ex.availableToBack().isEmpty()) {
                        bestBack = String.valueOf(ex.availableToBack().get(0).price());
                    }
                    if (!ex.availableToLay().isEmpty()) {
                        bestLay = String.valueOf(ex.availableToLay().get(0).price());
                    }
                }

                log.info("    Runner #{} | back={} | lay={}",
                        selectionId, bestBack, bestLay);
            }
        }

        log.info("===========================================================");
        log.info("  Betfair integration ready - connection verified.");
        log.info("===========================================================");
    }

    // ── Match Metadata Enrichment (matchDate + odds) ────────────────────

    /**
     * Enriches group matches with Betfair metadata: match start time and best
     * back odds. Uses live data if available, otherwise falls back to
     * {@code fallback-odds.json}.
     *
     * <p>
     * Sets the following fields on each matched {@link GroupMatch}:
     * <ul>
     * <li>{@code matchDate} — from Betfair's {@code marketStartTime}
     * (ISO-8601)</li>
     * <li>{@code odds1} / {@code oddsDraw} / {@code odds2} — best back prices,
     * aligned to the GroupMatch's team1/team2 order</li>
     * </ul>
     *
     * @param groupMatches the full map of match-id {@link GroupMatch}
     */
    public void enrichMatchesWithBetfairData(Map<String, GroupMatch> groupMatches) {
        if (liveApiAvailable && sessionToken == null) {
            authenticate();
        }

        try {
            // ── Build reverse lookup: sorted team-pair match-id ─────────
            Map<String, String> pairToMatchId = new LinkedHashMap<>();
            for (var entry : groupMatches.entrySet()) {
                GroupMatch match = entry.getValue();
                String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
                pairToMatchId.put(key, entry.getKey());
            }

            // ── Fetch market catalogue (live if authenticated, else fallback) ─
            List<BetfairMarketCatalog> markets;
            List<BetfairMarketBook> fallbackBooks = null;
            boolean usingFallback = false;

            if (liveApiAvailable && sessionToken != null) {
                try {
                    markets = marketClient.listMarketCatalogue(sessionToken);
                } catch (Exception e) {
                    log.warn("Exception fetching market catalogue for enrichment: {}", e.getMessage());
                    markets = List.of();
                }
            } else {
                markets = List.of();
            }

            if (markets.isEmpty()) {
                try {
                    BetfairOddsSnapshot snapshot = readFallbackOddsSnapshot();
                    if (snapshot == null) {
                        log.warn("No fallback-odds.json found (filesystem or classpath)");
                        return;
                    }
                    markets = snapshot.catalogue();
                    fallbackBooks = snapshot.books();
                    usingFallback = true;
                } catch (Exception e) {
                    log.warn("Failed to read fallback-odds.json for enrichment: {}", e.getMessage());
                    return;
                }
            }

            if (markets.isEmpty())
                return;

            // selectionId RunnerMeta per market
            Map<String, Map<Long, RunnerMeta>> marketSelections = new LinkedHashMap<>();
            Map<String, GroupMatch> marketToMatch = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (BetfairMarketCatalog market : markets) {
                String marketId = market.marketId();
                String marketStartTime = market.marketStartTime();
                List<BetfairRunnerCatalog> runners = market.runners();

                Map<Long, RunnerMeta> selMap = new LinkedHashMap<>();
                String homeCode = null;
                String awayCode = null;

                for (BetfairRunnerCatalog runner : runners) {
                    String runnerName = runner.runnerName().trim();
                    long selectionId = runner.selectionId();
                    int sortPriority = runner.sortPriority();

                    if (runnerName.equalsIgnoreCase("Draw")
                            || runnerName.equalsIgnoreCase("The Draw")) {
                        selMap.put(selectionId, new RunnerMeta("DRAW", sortPriority));
                        continue;
                    }
                    String fifaCode = BetfairNamesToCodes.BETFAIR_TO_FIFA.get(runnerName);
                    if (fifaCode != null) {
                        selMap.put(selectionId, new RunnerMeta(fifaCode, sortPriority));
                        if (sortPriority == 1)
                            homeCode = fifaCode;
                        else if (sortPriority == 2)
                            awayCode = fifaCode;
                    }
                }

                if (homeCode != null && awayCode != null) {
                    String pairKey = sortedPair(homeCode, awayCode);
                    String matchId = pairToMatchId.get(pairKey);
                    if (matchId != null) {
                        GroupMatch groupMatch = groupMatches.get(matchId);
                        groupMatch.setMarketURL(createMarketURL(market));
                        marketSelections.put(marketId, selMap);
                        marketToMatch.put(marketId, groupMatch);
                        matchedMarketIds.add(marketId);

                        // Set matchDate on the GroupMatch
                        if (marketStartTime != null && !marketStartTime.isEmpty()) {
                            groupMatch.setMatchDate(marketStartTime);
                        }
                    }
                }
            }

            // ── Fetch market books and extract odds ──────────────────────
            if (usingFallback && fallbackBooks != null) {
                for (BetfairMarketBook book : fallbackBooks) {
                    enrichOddsFromBook(book, marketToMatch, marketSelections);
                }
            } else {
                int batchSize = 40;
                for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                    List<String> batch = matchedMarketIds.subList(
                            i, Math.min(i + batchSize, matchedMarketIds.size()));
                    List<BetfairMarketBook> books;
                    try {
                        books = marketClient.listMarketBook(sessionToken, batch);
                    } catch (Exception e) {
                        log.warn("Exception fetching market book for enrichment: {}", e.getMessage());
                        books = List.of();
                    }
                    for (BetfairMarketBook book : books) {
                        enrichOddsFromBook(book, marketToMatch, marketSelections);
                    }
                }
            }

            log.debug("enrichMatchesWithBetfairData: enriched {} match(es) with Betfair metadata",
                    matchedMarketIds.size());

        } catch (Exception e) {
            log.warn("Failed to enrich matches with Betfair metadata: {}", e.getMessage());
        }
    }

    /**
     * Extracts best back odds from a market book and sets them on the
     * corresponding GroupMatch, aligned to team1/team2 order.
     */
    private void enrichOddsFromBook(BetfairMarketBook book,
            Map<String, GroupMatch> marketToMatch,
            Map<String, Map<Long, RunnerMeta>> marketSelections) {
        String marketId = book.marketId();
        GroupMatch match = marketToMatch.get(marketId);
        if (match == null)
            return;

        Map<Long, RunnerMeta> selMap = marketSelections.get(marketId);
        if (selMap == null)
            return;

        Double homeOdds = null;
        Double drawOdds = null;
        Double awayOdds = null;

        for (BetfairRunnerBook runner : book.runners()) {
            long selId = runner.selectionId();
            RunnerMeta meta = selMap.get(selId);
            if (meta == null)
                continue;

            BetfairExchangePrices ex = runner.ex();
            if (ex == null || ex.availableToBack().isEmpty())
                continue;
            double bestBack = ex.availableToBack().get(0).price();
            if (bestBack <= 1.0)
                continue;

            switch (meta.sortPriority()) {
                case 1 -> homeOdds = bestBack;
                case 2 -> awayOdds = bestBack;
                case 3 -> drawOdds = bestBack;
            }
        }

        // Align odds to GroupMatch team1/team2 order
        String homeCode = null;
        for (var entry : selMap.entrySet()) {
            if (entry.getValue().sortPriority() == 1 && !"DRAW".equals(entry.getValue().fifaCode())) {
                homeCode = entry.getValue().fifaCode();
                break;
            }
        }

        if (homeCode != null && homeCode.equals(match.getTeam1Code())) {
            match.setOdds1(homeOdds);
            match.setOdds2(awayOdds);
        } else {
            match.setOdds1(awayOdds);
            match.setOdds2(homeOdds);
        }
        match.setOddsDraw(drawOdds);
        // NOTE: marketURL is already set during the catalogue scan above
        // (see enrichMatchesWithBetfairData), so we don't touch it here.
    }

    // ── Knockout Match Enrichment (betting URLs + odds) ──────────────────

    /**
     * Enriches knockout matches with Betfair metadata: betting page URL and
     * best back odds. Only matches where both teams are resolved (non-null)
     * are enriched. Uses live data if available, otherwise falls back to
     * {@code fallback-odds.json}.
     *
     * <p>
     * Sets the following fields on each matched {@link KnockoutMatch}:
     * <ul>
     * <li>{@code marketURL} — Betfair Exchange betting page URL</li>
     * <li>{@code odds1} / {@code oddsDraw} / {@code odds2} — best back prices,
     * aligned to the KnockoutMatch's team1/team2 order</li>
     * </ul>
     *
     * <p>
     * Existing values are cleared first so stale data doesn't persist when
     * teams change.
     *
     * @param knockoutMatches the full map of match-id {@link KnockoutMatch}
     */
    public void enrichKnockoutMatchesWithBetfairURLs(Map<Integer, KnockoutMatch> knockoutMatches) {
        // Always clear first so stale data doesn't persist when teams change
        for (KnockoutMatch km : knockoutMatches.values()) {
            km.setMarketURL(null);
            km.setOdds1(null);
            km.setOddsDraw(null);
            km.setOdds2(null);
        }

        // Build reverse lookup only for matches with both teams resolved
        Map<String, Integer> pairToMatchId = new LinkedHashMap<>();
        for (var entry : knockoutMatches.entrySet()) {
            KnockoutMatch match = entry.getValue();
            if (match.getTeam1Code() == null || match.getTeam2Code() == null) {
                continue;
            }
            String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
            pairToMatchId.put(key, entry.getKey());
        }

        if (pairToMatchId.isEmpty()) {
            return;
        }

        if (liveApiAvailable && sessionToken == null) {
            authenticate();
        }

        try {
            // ── Fetch market catalogue (live if authenticated, else fallback) ─
            List<BetfairMarketCatalog> markets;
            List<BetfairMarketBook> fallbackBooks = null;
            boolean usingFallback = false;

            if (liveApiAvailable && sessionToken != null) {
                try {
                    markets = marketClient.listMarketCatalogue(sessionToken);
                } catch (Exception e) {
                    log.warn("Exception fetching market catalogue for knockout enrichment: {}",
                            e.getMessage());
                    markets = List.of();
                }
            } else {
                markets = List.of();
            }

            if (markets.isEmpty()) {
                try {
                    BetfairOddsSnapshot snapshot = readFallbackOddsSnapshot();
                    if (snapshot == null) {
                        log.debug("No fallback-odds.json found for knockout enrichment");
                        return;
                    }
                    markets = snapshot.catalogue();
                    fallbackBooks = snapshot.books();
                    usingFallback = true;
                } catch (Exception e) {
                    log.warn("Failed to read fallback-odds.json for knockout enrichment: {}",
                            e.getMessage());
                    return;
                }
            }

            if (markets.isEmpty())
                return;

            // selectionId RunnerMeta per market, and market→match mapping
            Map<String, Map<Long, RunnerMeta>> marketSelections = new LinkedHashMap<>();
            Map<String, KnockoutMatch> marketToMatch = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (BetfairMarketCatalog market : markets) {
                String marketId = market.marketId();
                List<BetfairRunnerCatalog> runners = market.runners();

                Map<Long, RunnerMeta> selMap = new LinkedHashMap<>();
                String homeCode = null;
                String awayCode = null;

                for (BetfairRunnerCatalog runner : runners) {
                    String runnerName = runner.runnerName().trim();
                    long selectionId = runner.selectionId();
                    int sortPriority = runner.sortPriority();

                    if (runnerName.equalsIgnoreCase("Draw")
                            || runnerName.equalsIgnoreCase("The Draw")) {
                        selMap.put(selectionId, new RunnerMeta("DRAW", sortPriority));
                        continue;
                    }
                    String fifaCode = BetfairNamesToCodes.BETFAIR_TO_FIFA.get(runnerName);
                    if (fifaCode != null) {
                        selMap.put(selectionId, new RunnerMeta(fifaCode, sortPriority));
                        if (sortPriority == 1)
                            homeCode = fifaCode;
                        else if (sortPriority == 2)
                            awayCode = fifaCode;
                    }
                }

                if (homeCode != null && awayCode != null) {
                    String pairKey = sortedPair(homeCode, awayCode);
                    Integer matchId = pairToMatchId.get(pairKey);
                    if (matchId != null) {
                        KnockoutMatch km = knockoutMatches.get(matchId);
                        km.setMarketURL(createMarketURL(market));
                        marketSelections.put(marketId, selMap);
                        marketToMatch.put(marketId, km);
                        matchedMarketIds.add(marketId);
                    }
                }
            }

            // ── Fetch market books and extract odds ──────────────────────
            if (usingFallback && fallbackBooks != null) {
                for (BetfairMarketBook book : fallbackBooks) {
                    enrichKnockoutOddsFromBook(book, marketToMatch, marketSelections);
                }
            } else {
                int batchSize = 40;
                for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                    List<String> batch = matchedMarketIds.subList(
                            i, Math.min(i + batchSize, matchedMarketIds.size()));
                    List<BetfairMarketBook> books;
                    try {
                        books = marketClient.listMarketBook(sessionToken, batch);
                    } catch (Exception e) {
                        log.warn("Exception fetching market book for knockout enrichment: {}",
                                e.getMessage());
                        books = List.of();
                    }
                    for (BetfairMarketBook book : books) {
                        enrichKnockoutOddsFromBook(book, marketToMatch, marketSelections);
                    }
                }
            }

            log.debug("enrichKnockoutMatchesWithBetfairURLs: enriched {} knockout match(es) with URLs/odds",
                    matchedMarketIds.size());

        } catch (Exception e) {
            log.warn("Failed to enrich knockout matches with Betfair data: {}", e.getMessage());
        }
    }

    /**
     * Extracts best back odds from a market book and sets them on the
     * corresponding KnockoutMatch, aligned to team1/team2 order.
     */
    private void enrichKnockoutOddsFromBook(BetfairMarketBook book,
            Map<String, KnockoutMatch> marketToMatch,
            Map<String, Map<Long, RunnerMeta>> marketSelections) {
        String marketId = book.marketId();
        KnockoutMatch match = marketToMatch.get(marketId);
        if (match == null)
            return;

        Map<Long, RunnerMeta> selMap = marketSelections.get(marketId);
        if (selMap == null)
            return;

        Double homeOdds = null;
        Double drawOdds = null;
        Double awayOdds = null;

        for (BetfairRunnerBook runner : book.runners()) {
            long selId = runner.selectionId();
            RunnerMeta meta = selMap.get(selId);
            if (meta == null)
                continue;

            BetfairExchangePrices ex = runner.ex();
            if (ex == null || ex.availableToBack().isEmpty())
                continue;
            double bestBack = ex.availableToBack().get(0).price();
            if (bestBack <= 1.0)
                continue;

            switch (meta.sortPriority()) {
                case 1 -> homeOdds = bestBack;
                case 2 -> awayOdds = bestBack;
                case 3 -> drawOdds = bestBack;
            }
        }

        // Align odds to KnockoutMatch team1/team2 order
        String homeCode = null;
        for (var entry : selMap.entrySet()) {
            if (entry.getValue().sortPriority() == 1 && !"DRAW".equals(entry.getValue().fifaCode())) {
                homeCode = entry.getValue().fifaCode();
                break;
            }
        }

        if (homeCode != null && homeCode.equals(match.getTeam1Code())) {
            match.setOdds1(homeOdds);
            match.setOdds2(awayOdds);
        } else {
            match.setOdds1(awayOdds);
            match.setOdds2(homeOdds);
        }
        match.setOddsDraw(drawOdds);
    }

    // ── Diagnostic: dump Betfair runner names for World Cup markets ───────

    /** Matches score-line patterns like "0 - 0", "1 - 2", etc. */
    private static final Pattern SCORE_LINE = Pattern.compile("\\d+\\s*-\\s*\\d+");

    /**
     * Searches for World Cup / FIFA soccer MATCH_ODDS markets and returns a
     * deduplicated set of runner names that look like actual team names
     * (filters out "Draw", "The Draw", score lines, Over/Under, etc.).
     * <p>
     * Temporary diagnostic intended to be removed once we have a stable
     * code-to-name mapping.
     */
    Set<String> collectWorldCupRunnerNames() {
        if (!liveApiAvailable || sessionToken == null) {
            return Set.of();
        }

        Set<String> teamNames = new LinkedHashSet<>();

        for (String query : List.of("World Cup", "FIFA")) {
            try {
                List<BetfairMarketCatalog> markets = marketClient.listMarketCatalogue(sessionToken, query);

                for (BetfairMarketCatalog market : markets) {
                    // Only process MATCH_ODDS markets (team vs team)
                    if (!"Match Odds".equals(market.marketName()))
                        continue;

                    List<BetfairRunnerCatalog> runners = market.runners();
                    if (runners == null || runners.isEmpty())
                        continue;

                    for (BetfairRunnerCatalog runner : runners) {
                        String name = runner.runnerName().trim();
                        if (name.isBlank())
                            continue;
                        if (looksLikeTeamName(name)) {
                            teamNames.add(name);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to query textQuery='{}': {}", query, e.getMessage());
            }
        }
        return teamNames;
    }

    private boolean looksLikeTeamName(String name) {
        // Filter out "The Draw" / "Draw"
        if (name.equalsIgnoreCase("Draw") || name.equalsIgnoreCase("The Draw"))
            return false;
        // Filter out score lines like "0 - 0", "1 - 2"
        if (SCORE_LINE.matcher(name).matches())
            return false;
        // Filter out Over/Under
        if (name.startsWith("Over ") || name.startsWith("Under "))
            return false;
        // Filter out generic betting labels
        if (name.equals("Yes") || name.equals("No"))
            return false;
        if (name.equals("Odd") || name.equals("Even"))
            return false;
        // Filter out handicap suffixes like "+1", "-2"
        if (name.matches(".*\\s[+-]\\d$"))
            return false;
        // Filter out combined-result patterns like "Team/Draw", "Team/Over 2.5 Goals"
        if (name.contains("/") && (name.contains("Over") || name.contains("Under")
                || name.contains("Draw") || name.contains("Yes") || name.contains("No")))
            return false;
        // Filter out "Any Other ..." and "Any Unquoted"
        if (name.startsWith("Any "))
            return false;
        // Filter out "Home or ...", "Draw or ..."
        if (name.startsWith("Home or ") || name.startsWith("Draw or "))
            return false;
        return true;
    }

    // ── Group Stage Simulation via Betfair Odds ──────────────────────────

    /**
     * Holds per-runner metadata extracted from the market catalogue.
     * Used to map book runners (identified only by selectionId) back to
     * their sortPriority and FIFA team code.
     *
     * @param fifaCode     the 3-letter FIFA team code, or "DRAW" for sortPriority 3
     * @param sortPriority Betfair sortPriority: 1 = Home, 2 = Away, 3 = Draw
     */
    private record RunnerMeta(String fifaCode, int sortPriority) {
    }

    /** Realistic score-lines for a Team A (home) win. */
    private static final int[][] TEAM_A_WIN_SCORES = {
            { 1, 0 }, { 2, 0 }, { 2, 1 }, { 3, 0 }, { 3, 1 }, { 1, 0 }, { 2, 1 }, { 1, 0 }, {3, 2}, {4, 1}, {4, 2}, {5, 1}, {5, 2}
    };

    /** Realistic score-lines for a draw. */
    private static final int[][] DRAW_SCORES = {
            { 0, 0 }, { 1, 1 }, { 1, 1 }, { 2, 2 }, { 0, 0 }, { 1, 1 }, {3, 3}, {4, 4}
    };

    /** Realistic score-lines for a Team B (away) win. */
    private static final int[][] TEAM_B_WIN_SCORES = {
            { 0, 1 }, { 0, 2 }, { 1, 2 }, { 0, 3 }, { 1, 3 }, { 0, 1 }, { 1, 2 }, { 0, 1 }, {2, 3}, {1, 4}, {2, 4}, {1, 5}, {2, 5}
    };

    /**
     * Simulates all 72 group-stage matches using live Betfair odds.
     *
     * <p>
     * For each match the method:
     * <ol>
     * <li>Fetches the MATCH_ODDS market catalogue for World Cup 2026</li>
     * <li>Maps Betfair runner names to internal FIFA team codes</li>
     * <li>Retrieves best Back prices via {@code listMarketBook} (batched, max
     * 40)</li>
     * <li>Converts decimal odds implied probabilities, normalises to sum =
     * 1.0</li>
     * <li>Rolls a random double to pick the outcome (Team A win / Draw / Team B
     * win)</li>
     * <li>Picks a realistic score-line for the chosen outcome</li>
     * </ol>
     *
     * <p>
     * <b>Fallback:</b> If no odds are available for a match (or the market
     * book is empty), the three outcomes default to an equal 33.3 % probability.
     *
     * @param groupMatches the full map of match-id {@link GroupMatch} from
     *                     {@link dev.scaffoldkit.fifa.service.GroupStageService}
     * @return a map of match-id {@code int[2]} where {@code [0]} is team 1's
     *         score and {@code [1]} is team 2's score
     */
    public Map<String, int[]> simulateGroupStageOdds(Map<String, GroupMatch> groupMatches) {
        Map<String, int[]> results = new LinkedHashMap<>();
        Random random = new Random();

        // ── Ensure we have a valid session (skip if live API unavailable) ──
        if (liveApiAvailable && sessionToken == null) {
            authenticate();
        }

        try {
            // ── Build reverse lookup: sorted team-pair match-id ─────────
            Map<String, String> pairToMatchId = new LinkedHashMap<>();
            for (var entry : groupMatches.entrySet()) {
                GroupMatch match = entry.getValue();
                String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
                pairToMatchId.put(key, entry.getKey());
            }

            // ── Fetch market catalogue (live if authenticated, else fallback) ─
            List<BetfairMarketCatalog> markets;
            List<BetfairMarketBook> fallbackBooks = null;
            boolean usingFallback = false;

            if (liveApiAvailable && sessionToken != null) {
                try {
                    markets = marketClient.listMarketCatalogue(sessionToken);
                } catch (Exception e) {
                    log.warn("Exception fetching market catalogue: {}", e.getMessage());
                    markets = List.of();
                }
            } else {
                markets = List.of();
            }

            if (markets.isEmpty()) {
                log.info("No live market catalogue available (liveApi={}, sessionToken={}). " +
                        "Falling back to local snapshot...", liveApiAvailable,
                        sessionToken != null ? "present" : "null");
                try {
                    BetfairOddsSnapshot snapshot = readFallbackOddsSnapshot();
                    if (snapshot == null) {
                        log.error("No fallback-odds.json found (filesystem or classpath)");
                        appEvents.emitError("Betfair",
                                "Failed to load odds data. Using equal-probability simulation.");
                        groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                        return results;
                    }
                    markets = snapshot.catalogue();
                    fallbackBooks = snapshot.books();
                    usingFallback = true;
                    appEvents.emitInfo("Betfair",
                            "Using saved odds snapshot (live Betfair data unavailable). " +
                                    "Simulation results are based on previously captured data.");
                } catch (Exception e) {
                    log.error("Failed to read fallback-odds.json", e);
                    appEvents.emitError("Betfair",
                            "Failed to load odds data. Using equal-probability simulation.");
                    groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                    return results;
                }
            }

            if (markets.isEmpty()) {
                log.warn("No market catalogue found - using fallback for all matches");
                groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                return results;
            }
            log.info(" simulateGroupStageOdds: received {} market(s) from Betfair (fallback={})", markets.size(),
                    usingFallback);

            // selectionId RunnerMeta (FIFA code + sortPriority), per market
            // sortPriority 1 = Home, 2 = Away, 3 = Draw — per Betfair API spec
            Map<String, Map<Long, RunnerMeta>> marketSelections = new LinkedHashMap<>();
            Map<String, String> marketToMatchId = new LinkedHashMap<>();
            Map<String, GroupMatch> marketToMatch = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (BetfairMarketCatalog market : markets) {
                String marketId = market.marketId();
                List<BetfairRunnerCatalog> runners = market.runners();

                Map<Long, RunnerMeta> selMap = new LinkedHashMap<>();
                String homeCode = null;
                String awayCode = null;

                for (BetfairRunnerCatalog runner : runners) {
                    String runnerName = runner.runnerName().trim();
                    long selectionId = runner.selectionId();
                    int sortPriority = runner.sortPriority();

                    if (runnerName.equalsIgnoreCase("Draw")
                            || runnerName.equalsIgnoreCase("The Draw")) {
                        selMap.put(selectionId, new RunnerMeta("DRAW", sortPriority));
                        continue;
                    }
                    String fifaCode = BetfairNamesToCodes.BETFAIR_TO_FIFA.get(runnerName);
                    if (fifaCode != null) {
                        selMap.put(selectionId, new RunnerMeta(fifaCode, sortPriority));
                        // Explicitly assign Home/Away based on sortPriority, not array order
                        if (sortPriority == 1) {
                            homeCode = fifaCode;
                        } else if (sortPriority == 2) {
                            awayCode = fifaCode;
                        }
                    }
                }

                if (homeCode != null && awayCode != null) {
                    String pairKey = sortedPair(homeCode, awayCode);
                    String matchId = pairToMatchId.get(pairKey);
                    if (matchId != null) {
                        GroupMatch groupMatch = groupMatches.get(matchId);
                        marketSelections.put(marketId, selMap);
                        marketToMatchId.put(marketId, matchId);
                        marketToMatch.put(marketId, groupMatch);
                        matchedMarketIds.add(marketId);

                        log.debug("  Market {} match {} | Betfair Home={} Away={} | " +
                                "GroupMatch team1={} team2={}",
                                marketId, matchId, homeCode, awayCode,
                                groupMatch.getTeam1Code(), groupMatch.getTeam2Code());
                    }
                }
            }

            log.info(" simulateGroupStageOdds: matched {} Betfair market(s) to group matches",
                    matchedMarketIds.size());

            // ── Batch-fetch market books (Betfair limit: 40 per call) ─────
            if (usingFallback && fallbackBooks != null) {
                for (BetfairMarketBook book : fallbackBooks) {
                    processBookNode(book, marketToMatchId, marketToMatch, marketSelections, random, results);
                }
            } else {
                int batchSize = 40;
                for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                    List<String> batch = matchedMarketIds.subList(
                            i, Math.min(i + batchSize, matchedMarketIds.size()));
                    List<BetfairMarketBook> books;
                    try {
                        books = marketClient.listMarketBook(sessionToken, batch);
                    } catch (Exception e) {
                        log.warn("Exception fetching market book: {}", e.getMessage());
                        books = List.of();
                    }

                    for (BetfairMarketBook book : books) {
                        processBookNode(book, marketToMatchId, marketToMatch, marketSelections, random, results);
                    }
                }
            }

            // ── Fill in any unmatched matches with fallback ───────────────
            int oddsBased = 0;
            for (var entry : groupMatches.entrySet()) {
                if (!results.containsKey(entry.getKey())) {
                    results.put(entry.getKey(), simulateWithFallback(random));
                } else {
                    oddsBased++;
                }
            }

            log.info(" simulateGroupStageOdds: done - {} from odds, {} from fallback",
                    oddsBased, groupMatches.size() - oddsBased);

        } catch (Exception e) {
            log.error("Error during Betfair group stage simulation - filling gaps with fallback", e);
            appEvents.emitError("Betfair",
                    "Error during odds simulation: " + shortenMessage(e.getMessage()) +
                            ". Unresolved matches use equal probability.");
            for (var entry : groupMatches.entrySet()) {
                if (!results.containsKey(entry.getKey())) {
                    results.put(entry.getKey(), simulateWithFallback(random));
                }
            }
        }

        return results;
    }

    private void processBookNode(BetfairMarketBook book,
            Map<String, String> marketToMatchId,
            Map<String, GroupMatch> marketToMatch,
            Map<String, Map<Long, RunnerMeta>> marketSelections,
            Random random, Map<String, int[]> results) {
        String marketId = book.marketId();
        String matchId = marketToMatchId.get(marketId);
        if (matchId == null)
            return;

        GroupMatch match = marketToMatch.get(marketId);
        Map<Long, RunnerMeta> selMap = marketSelections.get(marketId);

        // Extract odds using sortPriority: 1=Home, 2=Away, 3=Draw
        Double homeOdds = null;
        Double drawOdds = null;
        Double awayOdds = null;

        for (BetfairRunnerBook runner : book.runners()) {
            long selId = runner.selectionId();
            RunnerMeta meta = selMap.get(selId);
            if (meta == null)
                continue;

            BetfairExchangePrices ex = runner.ex();
            if (ex == null || ex.availableToBack().isEmpty())
                continue;
            double bestBack = ex.availableToBack().get(0).price();
            if (bestBack <= 1.0)
                continue; // invalid / no-market

            switch (meta.sortPriority()) {
                case 1 -> homeOdds = bestBack; // sortPriority 1 = Home
                case 2 -> awayOdds = bestBack; // sortPriority 2 = Away
                case 3 -> drawOdds = bestBack; // sortPriority 3 = Draw
            }
        }

        // Map Betfair Home/Away to the GroupMatch's team1/team2 order.
        // Betfair's sortPriority 1 (Home) corresponds to the first team listed
        // in the event name (e.g. "Mexico v South Africa" Mexico is Home).
        // We match markets to group matches by sorted team-pair (order-independent),
        // so we must now align odds with the group match's team1/team2 slots.
        Double team1Odds;
        Double team2Odds;

        if (homeOdds != null && match.getTeam1Code().equals(match.getTeam2Code())) {
            // Edge case: same team (shouldn't happen) — treat homeOdds as team1
            team1Odds = homeOdds;
            team2Odds = awayOdds;
        } else {
            // Check if Betfair Home (sortPriority 1) is team1 or team2 in our GroupMatch
            // We need to look up which FIFA code the sortPriority-1 runner maps to
            String homeCode = null;
            for (var entry : selMap.entrySet()) {
                if (entry.getValue().sortPriority() == 1 && !"DRAW".equals(entry.getValue().fifaCode())) {
                    homeCode = entry.getValue().fifaCode();
                    break;
                }
            }

            if (homeCode != null && homeCode.equals(match.getTeam1Code())) {
                // Betfair Home aligns with our team1 — no swap needed
                team1Odds = homeOdds;
                team2Odds = awayOdds;
            } else {
                // Betfair Home maps to our team2 — swap so odds align correctly
                team1Odds = awayOdds;
                team2Odds = homeOdds;
            }
        }

        int[] score = simulateMatch(random, team1Odds, drawOdds, team2Odds);
        results.put(matchId, score);

        log.debug("  {} ({} vs {}): odds T1={} D={} T2={} -> {}-{}",
                matchId, match.getTeam1Code(), match.getTeam2Code(),
                team1Odds, drawOdds, team2Odds, score[0], score[1]);
    }

    // ── Simulation helpers ───────────────────────────────────────────────

    /**
     * Simulates a single match given the best back odds for each outcome.
     * If any odds are null the method falls back to equal 33.3 % probabilities.
     */
    private int[] simulateMatch(Random random, Double team1Odds, Double drawOdds, Double team2Odds) {
        double p1, pDraw, p2;

        if (team1Odds == null || drawOdds == null || team2Odds == null) {
            // Fallback: equal coin-flip
            p1 = pDraw = p2 = 1.0 / 3.0;
        } else {
            // Convert decimal odds implied probability, then normalise
            p1 = 1.0 / team1Odds;
            pDraw = 1.0 / drawOdds;
            p2 = 1.0 / team2Odds;
            double total = p1 + pDraw + p2;
            p1 /= total;
            pDraw /= total;
            p2 /= total;
        }

        double roll = random.nextDouble();

        if (roll < p1) {
            return TEAM_A_WIN_SCORES[random.nextInt(TEAM_A_WIN_SCORES.length)].clone();
        } else if (roll < p1 + pDraw) {
            return DRAW_SCORES[random.nextInt(DRAW_SCORES.length)].clone();
        } else {
            return TEAM_B_WIN_SCORES[random.nextInt(TEAM_B_WIN_SCORES.length)].clone();
        }
    }

    /** Convenience: simulate with equal (fallback) probabilities. */
    private int[] simulateWithFallback(Random random) {
        return simulateMatch(random, null, null, null);
    }

    /** Returns a sorted key for a team-code pair (order-independent matching). */
    private static String sortedPair(String code1, String code2) {
        return code1.compareTo(code2) < 0
                ? code1 + "_" + code2
                : code2 + "_" + code1;
    }

    /** Truncates a message to a user-friendly length (no stack traces). */
    private static String shortenMessage(String msg) {
        if (msg == null)
            return "Unknown error";
        // Take only the first line if multi-line
        int newline = msg.indexOf('\n');
        String firstLine = newline > 0 ? msg.substring(0, newline).trim() : msg;
        // Cap at 150 characters
        if (firstLine.length() > 150) {
            return firstLine.substring(0, 147) + "...";
        }
        return firstLine;
    }
}