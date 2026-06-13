package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.service.AppEventService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/**
 * Top-level orchestrator for the Betfair Exchange API integration.
 *
 * <p>On application startup this service:
 * <ol>
 *   <li>Authenticates with Betfair via mutual-TLS certlogin</li>
 *   <li>Fetches the soccer market catalogue (Event Type 1 – MATCH_ODDS)</li>
 *   <li>Fetches market book data (current best back/lay prices)</li>
 *   <li>Logs key data at each step for verification</li>
 * </ol>
 *
 * <p>In the {@code prod} profile, the Betfair API dependencies (auth client,
 * market client, SSL config) are not loaded, so this service operates in
 * <b>fallback-only mode</b> — all live API calls are skipped and data is read
 * from {@code fallback-odds.json} exclusively.
 *
 * <p>This service is intentionally isolated from the primary tournament engine
 * and can be enabled/disabled via configuration.
 */
@Service
public class BetfairIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(BetfairIntegrationService.class);

    private final AppEventService appEvents;
    private final ObjectMapper objectMapper;

    /** Live API clients — absent in the prod profile (Betfair blocked on Railway.app). */
    private final BetfairAuthClient authClient;
    private final BetfairMarketClient marketClient;

    /** Whether the live Betfair API is available (false in prod). */
    private final boolean liveApiAvailable;

    /** Path to the filesystem copy of fallback-odds.json (inside the persistent data directory). */
    private final Path fallbackOddsPath;

    /** Currently active session token (cached after login). */
    private volatile String sessionToken;

    public BetfairIntegrationService(
            ObjectProvider<BetfairAuthClient> authClientProvider,
            ObjectProvider<BetfairMarketClient> marketClientProvider,
            AppEventService appEvents,
            @Value("${app.data.dir:./data}") String dataDir) {
        this.authClient = authClientProvider.getIfAvailable();
        this.marketClient = marketClientProvider.getIfAvailable();
        this.appEvents = appEvents;
        this.liveApiAvailable = this.authClient != null && this.marketClient != null;
        this.fallbackOddsPath = Paths.get(dataDir, "fallback-odds.json");
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Runs the full authentication + data-fetch pipeline on startup
     * so we can immediately verify the connection in logs.
     *
     * <p>In the prod profile this is a no-op — the live Betfair API is
     * permanently blocked from Railway.app hosting.
     */
    @PostConstruct
    void init() {
        if (!liveApiAvailable) {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("  Betfair Integration Service – PRODUCTION MODE");
            log.info("  Live Betfair API is disabled (blocked on Railway.app).");
            log.info("  All odds features will use fallback-odds.json.");
            log.info("═══════════════════════════════════════════════════════════");
            return;
        }

        log.info("═══════════════════════════════════════════════════════════");
        log.info("  Betfair Integration Service – initialising...");
        log.info("═══════════════════════════════════════════════════════════");

        try {
            authenticate();
            if (sessionToken != null) {
                fetchAndLogMarketCatalogue();
                appEvents.emitInfo("Betfair",
                        "Successfully connected to Betfair Exchange API.");
            }
        } catch (Exception e) {
            log.warn("Betfair integration did not initialise – the app will continue " +
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
    public void snapshotOddsLocally() {
        if (!liveApiAvailable) {
            log.warn("Cannot snapshot odds: Betfair live API is not available in this environment.");
            appEvents.emitWarning("Betfair",
                    "Cannot create odds snapshot in production. Run locally to capture odds.");
            return;
        }

        log.info("Starting local snapshot of Betfair odds...");
        if (sessionToken == null) {
            authenticate();
        }
        if (sessionToken == null) {
            log.error("Failed to authenticate for snapshot.");
            appEvents.emitWarning("Betfair",
                    "Cannot create odds snapshot: Betfair authentication failed.");
            return;
        }

        try {
            // We'll query "World Cup" and get the market catalogue, then fetch books for those markets,
            // and combine them into a single JSON object.
            String catalogueJson = marketClient.listMarketCatalogue(sessionToken);
            if (catalogueJson == null) {
                log.error("Failed to fetch market catalogue for snapshot.");
                return;
            }

            var markets = objectMapper.readTree(catalogueJson);
            List<String> matchedMarketIds = new ArrayList<>();
            for (var market : markets) {
                matchedMarketIds.add(market.path("marketId").asText());
            }

            var rootNode = objectMapper.createObjectNode();
            rootNode.set("catalogue", markets);

            var booksArray = objectMapper.createArrayNode();

            int batchSize = 40;
            for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                List<String> batch = matchedMarketIds.subList(i, Math.min(i + batchSize, matchedMarketIds.size()));
                String bookJson = marketClient.listMarketBook(sessionToken, batch);
                if (bookJson != null) {
                    var books = objectMapper.readTree(bookJson);
                    for (var book : books) {
                        booksArray.add(book);
                    }
                }
            }

            rootNode.set("books", booksArray);
            rootNode.put("snapshotTimestamp", java.time.Instant.now().toString());

            Path path = Paths.get("src/main/resources/fallback-odds.json");
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Successfully saved snapshot to {}", path.toAbsolutePath());

        } catch (Exception e) {
            log.error("Error during snapshot", e);
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

        log.info("→ Step 1: Authenticating with Betfair (mTLS certlogin)...");
        sessionToken = authClient.login();

        if (sessionToken != null) {
            log.info("✓ Authentication successful – session token length: {}",
                    sessionToken.length());
            return true;
        }

        log.error("✗ Authentication failed – check credentials and certificates");
        appEvents.emitWarning("Betfair",
                "Authentication with Betfair API failed. Check credentials and certificates.");
        return false;
    }

    /**
     * Fetches the soccer match-odds market catalogue and logs a summary.
     *
     * @return raw JSON of the catalogue, or {@code null}
     */
    public String fetchMarketCatalogue() {
        if (!liveApiAvailable) return null;
        ensureAuthenticated();
        return marketClient.listMarketCatalogue(sessionToken);
    }

    /**
     * Fetches market book (odds) for the given market IDs.
     *
     * @param marketIds the Betfair market IDs
     * @return raw JSON of the market book, or {@code null}
     */
    public String fetchMarketBook(List<String> marketIds) {
        if (!liveApiAvailable) return null;
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
                    "Not authenticated – call authenticate() first");
        }
    }

    /**
     * Reads the fallback-odds.json content, trying the persistent filesystem
     * location first (so the file can be updated without redeploy), then
     * falling back to the classpath resource (bundled in the JAR).
     *
     * @return the JSON string, or {@code null} if no source is available
     */
    private String readFallbackOddsJson() {
        // 1. Try filesystem (persistent volume in prod, local data dir in dev)
        if (Files.exists(fallbackOddsPath)) {
            try {
                String json = Files.readString(fallbackOddsPath, java.nio.charset.StandardCharsets.UTF_8);
                log.info("Read fallback-odds.json from filesystem: {}", fallbackOddsPath.toAbsolutePath());
                return json;
            } catch (Exception e) {
                log.warn("Failed to read fallback-odds.json from filesystem ({}): {}",
                        fallbackOddsPath.toAbsolutePath(), e.getMessage());
            }
        }

        // 2. Fallback: classpath resource (bundled in JAR, immutable)
        try {
            ClassPathResource resource = new ClassPathResource("fallback-odds.json");
            try (var is = resource.getInputStream()) {
                String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("Read fallback-odds.json from classpath (filesystem file not found at {})",
                        fallbackOddsPath.toAbsolutePath());
                return json;
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

    /**
     * Fetches the catalogue and logs a human-readable summary of discovered
     * markets, then fetches the market book for the first few and logs prices.
     */
    private void fetchAndLogMarketCatalogue() {
        log.info("→ Step 2: Fetching soccer market catalogue...");

        String catalogueJson = marketClient.listMarketCatalogue(sessionToken);
        if (catalogueJson == null) {
            log.error("✗ Failed to fetch market catalogue");
            return;
        }

        try {
            var markets = objectMapper.readTree(catalogueJson);
            int count = markets.size();
            log.info("✓ Received {} soccer match-odds market(s)", count);

            List<String> marketIds = new ArrayList<>();
            int logged = 0;
            for (var market : markets) {
                String marketId = market.path("marketId").asText();
                String marketName = market.path("marketName").asText();
                String startTime = market.path("event").path("openDate").asText("");
                String eventName = market.path("event").path("name").asText("");

                marketIds.add(marketId);

                if (logged < 10) {
                    log.info("  [{}/{}] {} | {} | {} | start={}",
                            logged + 1, count, marketId, eventName,
                            marketName, startTime);
                    logged++;
                }
            }

            // Fetch odds for first batch of markets
            if (!marketIds.isEmpty()) {
                fetchAndLogMarketBook(marketIds.subList(0, Math.min(5, marketIds.size())));
            }
        } catch (Exception e) {
            log.error("Failed to parse market catalogue", e);
        }
    }

    private void fetchAndLogMarketBook(List<String> marketIds) {
        log.info("→ Step 3: Fetching market book for {} market(s)...", marketIds.size());

        String bookJson = marketClient.listMarketBook(sessionToken, marketIds);
        if (bookJson == null) {
            log.error("✗ Failed to fetch market book");
            return;
        }

        try {
            var books = objectMapper.readTree(bookJson);
            log.info("✓ Received market book data for {} market(s)", books.size());

            for (var book : books) {
                String marketId = book.path("marketId").asText();
                boolean inPlay = book.path("inplay").asBoolean();
                var runners = book.path("runners");
                log.info("  Market {} | inPlay={} | {} runner(s)",
                        marketId, inPlay, runners.size());

                for (var runner : runners) {
                    String runnerName = runner.path("runnerName").asText("");
                    long selectionId = runner.path("selectionId").asLong();

                    // Best back price
                    var ex = runner.path("ex");
                    var availableToBack = ex.path("availableToBack");
                    String bestBack = availableToBack.size() > 0
                            ? availableToBack.get(0).path("price").asText()
                            : "n/a";

                    // Best lay price
                    var availableToLay = ex.path("availableToLay");
                    String bestLay = availableToLay.size() > 0
                            ? availableToLay.get(0).path("price").asText()
                            : "n/a";

                    log.info("    Runner #{} ({}) | back={} | lay={}",
                            selectionId, runnerName, bestBack, bestLay);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse market book", e);
        }

        log.info("═══════════════════════════════════════════════════════════");
        log.info("  Betfair integration ready – connection verified.");
        log.info("═══════════════════════════════════════════════════════════");
    }

    // ── Match Metadata Enrichment (matchDate + odds) ────────────────────

    /**
     * Enriches group matches with Betfair metadata: match start time and best
     * back odds. Uses live data if available, otherwise falls back to
     * {@code fallback-odds.json}.
     *
     * <p>Sets the following fields on each matched {@link GroupMatch}:
     * <ul>
     *   <li>{@code matchDate} — from Betfair's {@code marketStartTime} (ISO-8601)</li>
     *   <li>{@code odds1} / {@code oddsDraw} / {@code odds2} — best back prices,
     *       aligned to the GroupMatch's team1/team2 order</li>
     * </ul>
     *
     * @param groupMatches the full map of match-id → {@link GroupMatch}
     */
    public void enrichMatchesWithBetfairData(Map<String, GroupMatch> groupMatches) {
        if (liveApiAvailable && sessionToken == null) {
            authenticate();
        }

        try {
            // ── Build reverse lookup: sorted team-pair → match-id ─────────
            Map<String, String> pairToMatchId = new LinkedHashMap<>();
            for (var entry : groupMatches.entrySet()) {
                GroupMatch match = entry.getValue();
                String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
                pairToMatchId.put(key, entry.getKey());
            }

            // ── Fetch market catalogue (live if authenticated, else fallback) ─
            String catalogueJson = null;
            if (liveApiAvailable && sessionToken != null) {
                try {
                    catalogueJson = marketClient.listMarketCatalogue(sessionToken);
                } catch (Exception e) {
                    log.warn("Exception fetching market catalogue for enrichment: {}", e.getMessage());
                }
            }

            var rootNode = objectMapper.createObjectNode();
            boolean usingFallback = false;

            if (catalogueJson == null) {
                try {
                    String fallbackJson = readFallbackOddsJson();
                    if (fallbackJson == null) {
                        log.warn("No fallback-odds.json found (filesystem or classpath)");
                        return;
                    }
                    rootNode = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fallbackJson);
                    usingFallback = true;
                } catch (Exception e) {
                    log.warn("Failed to read fallback-odds.json for enrichment: {}", e.getMessage());
                    return;
                }
            }

            var markets = usingFallback ? rootNode.path("catalogue") : objectMapper.readTree(catalogueJson);
            if (markets.isMissingNode() || markets.isEmpty()) return;

            // selectionId → RunnerMeta per market
            Map<String, Map<Long, RunnerMeta>> marketSelections = new LinkedHashMap<>();
            Map<String, GroupMatch> marketToMatch = new LinkedHashMap<>();
            // marketId → marketStartTime
            Map<String, String> marketStartTimes = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (var market : markets) {
                String marketId = market.path("marketId").asText();
                String marketStartTime = market.path("marketStartTime").asText("");
                var runners = market.path("runners");

                Map<Long, RunnerMeta> selMap = new LinkedHashMap<>();
                String homeCode = null;
                String awayCode = null;

                for (var runner : runners) {
                    String runnerName = runner.path("runnerName").asText("").trim();
                    long selectionId = runner.path("selectionId").asLong();
                    int sortPriority = runner.path("sortPriority").asInt(0);

                    if (runnerName.equalsIgnoreCase("Draw")
                            || runnerName.equalsIgnoreCase("The Draw")) {
                        selMap.put(selectionId, new RunnerMeta("DRAW", sortPriority));
                        continue;
                    }
                    String fifaCode = BetfairNamesToCodes.BETFAIR_TO_FIFA.get(runnerName);
                    if (fifaCode != null) {
                        selMap.put(selectionId, new RunnerMeta(fifaCode, sortPriority));
                        if (sortPriority == 1) homeCode = fifaCode;
                        else if (sortPriority == 2) awayCode = fifaCode;
                    }
                }

                if (homeCode != null && awayCode != null) {
                    String pairKey = sortedPair(homeCode, awayCode);
                    String matchId = pairToMatchId.get(pairKey);
                    if (matchId != null) {
                        GroupMatch groupMatch = groupMatches.get(matchId);
                        marketSelections.put(marketId, selMap);
                        marketToMatch.put(marketId, groupMatch);
                        marketStartTimes.put(marketId, marketStartTime);
                        matchedMarketIds.add(marketId);

                        // Set matchDate on the GroupMatch
                        if (!marketStartTime.isEmpty()) {
                            groupMatch.setMatchDate(marketStartTime);
                        }
                    }
                }
            }

            // ── Fetch market books and extract odds ──────────────────────
            if (usingFallback) {
                var books = rootNode.path("books");
                for (var book : books) {
                    enrichOddsFromBook(book, marketToMatch, marketSelections);
                }
            } else {
                int batchSize = 40;
                for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                    List<String> batch = matchedMarketIds.subList(
                            i, Math.min(i + batchSize, matchedMarketIds.size()));
                    String bookJson = null;
                    try {
                        bookJson = marketClient.listMarketBook(sessionToken, batch);
                    } catch (Exception e) {
                        log.warn("Exception fetching market book for enrichment: {}", e.getMessage());
                    }
                    if (bookJson == null) continue;

                    var books = objectMapper.readTree(bookJson);
                    for (var book : books) {
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
     * Extracts best back odds from a market book node and sets them on the
     * corresponding GroupMatch, aligned to team1/team2 order.
     */
    private void enrichOddsFromBook(com.fasterxml.jackson.databind.JsonNode book,
                                     Map<String, GroupMatch> marketToMatch,
                                     Map<String, Map<Long, RunnerMeta>> marketSelections) {
        String marketId = book.path("marketId").asText();
        GroupMatch match = marketToMatch.get(marketId);
        if (match == null) return;

        Map<Long, RunnerMeta> selMap = marketSelections.get(marketId);
        if (selMap == null) return;

        Double homeOdds = null;
        Double drawOdds = null;
        Double awayOdds = null;

        for (var runner : book.path("runners")) {
            long selId = runner.path("selectionId").asLong();
            RunnerMeta meta = selMap.get(selId);
            if (meta == null) continue;

            var backPrices = runner.path("ex").path("availableToBack");
            if (backPrices.size() == 0) continue;
            double bestBack = backPrices.get(0).path("price").asDouble();
            if (bestBack <= 1.0) continue;

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
                String json = marketClient.listMarketCatalogue(sessionToken, query);
                if (json == null) continue;

                var markets = objectMapper.readTree(json);

                for (var market : markets) {
                    // Only process MATCH_ODDS markets (team vs team)
                    String marketName = market.path("marketName").asText();
                    if (!"Match Odds".equals(marketName)) continue;

                    var runners = market.path("runners");
                    if (runners.isMissingNode() || runners.isEmpty()) continue;

                    for (var runner : runners) {
                        String name = runner.path("runnerName").asText("").trim();
                        if (name.isBlank()) continue;
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
        if (name.equalsIgnoreCase("Draw") || name.equalsIgnoreCase("The Draw")) return false;
        // Filter out score lines like "0 - 0", "1 - 2"
        if (SCORE_LINE.matcher(name).matches()) return false;
        // Filter out Over/Under
        if (name.startsWith("Over ") || name.startsWith("Under ")) return false;
        // Filter out generic betting labels
        if (name.equals("Yes") || name.equals("No")) return false;
        if (name.equals("Odd") || name.equals("Even")) return false;
        // Filter out handicap suffixes like "+1", "-2"
        if (name.matches(".*\\s[+-]\\d$")) return false;
        // Filter out combined-result patterns like "Team/Draw", "Team/Over 2.5 Goals"
        if (name.contains("/") && (name.contains("Over") || name.contains("Under")
                || name.contains("Draw") || name.contains("Yes") || name.contains("No"))) return false;
        // Filter out "Any Other ..." and "Any Unquoted"
        if (name.startsWith("Any ")) return false;
        // Filter out "Home or ...", "Draw or ..."
        if (name.startsWith("Home or ") || name.startsWith("Draw or ")) return false;
        return true;
    }

    // ── Group Stage Simulation via Betfair Odds ──────────────────────────

    /**
     * Holds per-runner metadata extracted from the market catalogue.
     * Used to map book runners (identified only by selectionId) back to
     * their sortPriority and FIFA team code.
     *
     * @param fifaCode      the 3-letter FIFA team code, or "DRAW" for sortPriority 3
     * @param sortPriority  Betfair sortPriority: 1 = Home, 2 = Away, 3 = Draw
     */
    private record RunnerMeta(String fifaCode, int sortPriority) {}

    /** Realistic score-lines for a Team A (home) win. */
    private static final int[][] TEAM_A_WIN_SCORES = {
            {1, 0}, {2, 0}, {2, 1}, {3, 0}, {3, 1}, {1, 0}, {2, 1}, {1, 0}
    };

    /** Realistic score-lines for a draw. */
    private static final int[][] DRAW_SCORES = {
            {0, 0}, {1, 1}, {1, 1}, {2, 2}, {0, 0}, {1, 1}
    };

    /** Realistic score-lines for a Team B (away) win. */
    private static final int[][] TEAM_B_WIN_SCORES = {
            {0, 1}, {0, 2}, {1, 2}, {0, 3}, {1, 3}, {0, 1}, {1, 2}, {0, 1}
    };

    /**
     * Simulates all 72 group-stage matches using live Betfair odds.
     *
     * <p>For each match the method:
     * <ol>
     *   <li>Fetches the MATCH_ODDS market catalogue for World Cup 2026</li>
     *   <li>Maps Betfair runner names to internal FIFA team codes</li>
     *   <li>Retrieves best Back prices via {@code listMarketBook} (batched, max 40)</li>
     *   <li>Converts decimal odds → implied probabilities, normalises to sum = 1.0</li>
     *   <li>Rolls a random double to pick the outcome (Team A win / Draw / Team B win)</li>
     *   <li>Picks a realistic score-line for the chosen outcome</li>
     * </ol>
     *
     * <p><b>Fallback:</b> If no odds are available for a match (or the market
     * book is empty), the three outcomes default to an equal 33.3 % probability.
     *
     * @param groupMatches the full map of match-id → {@link GroupMatch} from
     *                     {@link dev.scaffoldkit.fifa.service.GroupStageService}
     * @return a map of match-id → {@code int[2]} where {@code [0]} is team 1's
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
            // ── Build reverse lookup: sorted team-pair → match-id ─────────
            Map<String, String> pairToMatchId = new LinkedHashMap<>();
            for (var entry : groupMatches.entrySet()) {
                GroupMatch match = entry.getValue();
                String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
                pairToMatchId.put(key, entry.getKey());
            }

            // ── Fetch market catalogue (live if authenticated, else fallback) ─
            String catalogueJson = null;
            if (liveApiAvailable && sessionToken != null) {
                try {
                    catalogueJson = marketClient.listMarketCatalogue(sessionToken);
                } catch (Exception e) {
                    log.warn("Exception fetching market catalogue: {}", e.getMessage());
                }
            }

            var rootNode = objectMapper.createObjectNode();
            boolean usingFallback = false;

            if (catalogueJson == null) {
                log.info("No live market catalogue available (liveApi={}, sessionToken={}). " +
                        "Falling back to local snapshot...", liveApiAvailable, sessionToken != null ? "present" : "null");
                try {
                    String fallbackJson = readFallbackOddsJson();
                    if (fallbackJson == null) {
                        log.error("No fallback-odds.json found (filesystem or classpath)");
                        appEvents.emitError("Betfair",
                                "Failed to load odds data. Using equal-probability simulation.");
                        groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                        return results;
                    }
                    rootNode = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fallbackJson);
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

            var markets = usingFallback ? rootNode.path("catalogue") : objectMapper.readTree(catalogueJson);
            if (markets.isMissingNode() || markets.isEmpty()) {
                log.warn("No market catalogue found – using fallback for all matches");
                groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                return results;
            }
            log.info(" simulateGroupStageOdds: received {} market(s) from Betfair (fallback={})", markets.size(), usingFallback);

            // selectionId → RunnerMeta (FIFA code + sortPriority), per market
            // sortPriority 1 = Home, 2 = Away, 3 = Draw — per Betfair API spec
            Map<String, Map<Long, RunnerMeta>> marketSelections = new LinkedHashMap<>();
            Map<String, String> marketToMatchId = new LinkedHashMap<>();
            Map<String, GroupMatch> marketToMatch = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (var market : markets) {
                String marketId = market.path("marketId").asText();
                var runners = market.path("runners");

                Map<Long, RunnerMeta> selMap = new LinkedHashMap<>();
                String homeCode = null;
                String awayCode = null;

                for (var runner : runners) {
                    String runnerName = runner.path("runnerName").asText("").trim();
                    long selectionId = runner.path("selectionId").asLong();
                    int sortPriority = runner.path("sortPriority").asInt(0);

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

                        log.debug("  Market {} → match {} | Betfair Home={} Away={} | " +
                                "GroupMatch team1={} team2={}",
                                marketId, matchId, homeCode, awayCode,
                                groupMatch.getTeam1Code(), groupMatch.getTeam2Code());
                    }
                }
            }

            log.info(" simulateGroupStageOdds: matched {} Betfair market(s) to group matches",
                    matchedMarketIds.size());

            // ── Batch-fetch market books (Betfair limit: 40 per call) ─────
            if (usingFallback) {
                var books = rootNode.path("books");
                for (var book : books) {
                    processBookNode(book, marketToMatchId, marketToMatch, marketSelections, random, results);
                }
            } else {
                int batchSize = 40;
                for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                    List<String> batch = matchedMarketIds.subList(
                            i, Math.min(i + batchSize, matchedMarketIds.size()));
                    String bookJson = null;
                    try {
                        bookJson = marketClient.listMarketBook(sessionToken, batch);
                    } catch (Exception e) {
                        log.warn("Exception fetching market book: {}", e.getMessage());
                    }
                    if (bookJson == null) continue;

                    var books = objectMapper.readTree(bookJson);
                    for (var book : books) {
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

            log.info(" simulateGroupStageOdds: done – {} from odds, {} from fallback",
                    oddsBased, groupMatches.size() - oddsBased);

        } catch (Exception e) {
            log.error("Error during Betfair group stage simulation – filling gaps with fallback", e);
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

    private void processBookNode(com.fasterxml.jackson.databind.JsonNode book,
                                 Map<String, String> marketToMatchId,
                                 Map<String, GroupMatch> marketToMatch,
                                 Map<String, Map<Long, RunnerMeta>> marketSelections,
                                 Random random, Map<String, int[]> results) {
        String marketId = book.path("marketId").asText();
        String matchId = marketToMatchId.get(marketId);
        if (matchId == null) return;

        GroupMatch match = marketToMatch.get(marketId);
        Map<Long, RunnerMeta> selMap = marketSelections.get(marketId);

        // Extract odds using sortPriority: 1=Home, 2=Away, 3=Draw
        Double homeOdds = null;
        Double drawOdds = null;
        Double awayOdds = null;

        for (var runner : book.path("runners")) {
            long selId = runner.path("selectionId").asLong();
            RunnerMeta meta = selMap.get(selId);
            if (meta == null) continue;

            var backPrices = runner.path("ex").path("availableToBack");
            if (backPrices.size() == 0) continue;
            double bestBack = backPrices.get(0).path("price").asDouble();
            if (bestBack <= 1.0) continue; // invalid / no-market

            switch (meta.sortPriority()) {
                case 1 -> homeOdds = bestBack;   // sortPriority 1 = Home
                case 2 -> awayOdds = bestBack;   // sortPriority 2 = Away
                case 3 -> drawOdds = bestBack;   // sortPriority 3 = Draw
            }
        }

        // Map Betfair Home/Away to the GroupMatch's team1/team2 order.
        // Betfair's sortPriority 1 (Home) corresponds to the first team listed
        // in the event name (e.g. "Mexico v South Africa" → Mexico is Home).
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

        log.debug("  {} ({} vs {}): odds T1={} D={} T2={} → {}-{}",
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
            // Convert decimal odds → implied probability, then normalise
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
        if (msg == null) return "Unknown error";
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