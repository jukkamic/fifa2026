package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scaffoldkit.fifa.model.GroupMatch;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * <p>On application startup this service:
 * <ol>
 *   <li>Authenticates with Betfair via mutual-TLS certlogin</li>
 *   <li>Fetches the soccer market catalogue (Event Type 1 – MATCH_ODDS)</li>
 *   <li>Fetches market book data (current best back/lay prices)</li>
 *   <li>Logs key data at each step for verification</li>
 * </ol>
 *
 * <p>This service is intentionally isolated from the primary tournament engine
 * and can be enabled/disabled via configuration.
 */
@Service
public class BetfairIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(BetfairIntegrationService.class);

    private final BetfairAuthClient authClient;
    private final BetfairMarketClient marketClient;
    private final ObjectMapper objectMapper;

    /** Currently active session token (cached after login). */
    private volatile String sessionToken;

    public BetfairIntegrationService(BetfairAuthClient authClient,
                                     BetfairMarketClient marketClient) {
        this.authClient = authClient;
        this.marketClient = marketClient;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Runs the full authentication + data-fetch pipeline on startup
     * so we can immediately verify the connection in logs.
     */
    @PostConstruct
    void init() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("  Betfair Integration Service – initialising...");
        log.info("═══════════════════════════════════════════════════════════");

        try {
            authenticate();
            if (sessionToken != null) {
                fetchAndLogMarketCatalogue();
            }
        } catch (Exception e) {
            log.warn("Betfair integration did not initialise – the app will continue " +
                    "without live odds. Reason: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Performs the certlogin authentication and caches the session token.
     *
     * @return {@code true} if authentication succeeded
     */
    public boolean authenticate() {
        log.info("→ Step 1: Authenticating with Betfair (mTLS certlogin)...");
        sessionToken = authClient.login();

        if (sessionToken != null) {
            log.info("✓ Authentication successful – session token length: {}",
                    sessionToken.length());
            return true;
        }

        log.error("✗ Authentication failed – check credentials and certificates");
        return false;
    }

    /**
     * Fetches the soccer match-odds market catalogue and logs a summary.
     *
     * @return raw JSON of the catalogue, or {@code null}
     */
    public String fetchMarketCatalogue() {
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

        // ── Ensure we have a valid session ────────────────────────────────
        if (sessionToken == null) {
            authenticate();
        }
        if (sessionToken == null) {
            log.warn("Cannot authenticate with Betfair – using fallback for all {} matches",
                    groupMatches.size());
            groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
            return results;
        }

        try {
            // ── Build reverse lookup: sorted team-pair → match-id ─────────
            Map<String, String> pairToMatchId = new LinkedHashMap<>();
            for (var entry : groupMatches.entrySet()) {
                GroupMatch match = entry.getValue();
                String key = sortedPair(match.getTeam1Code(), match.getTeam2Code());
                pairToMatchId.put(key, entry.getKey());
            }

            // ── Fetch market catalogue ────────────────────────────────────
            String catalogueJson = marketClient.listMarketCatalogue(sessionToken);
            if (catalogueJson == null) {
                log.warn("Failed to fetch market catalogue – using fallback for all matches");
                groupMatches.forEach((id, m) -> results.put(id, simulateWithFallback(random)));
                return results;
            }

            var markets = objectMapper.readTree(catalogueJson);
            log.info(" simulateGroupStageOdds: received {} market(s) from Betfair", markets.size());

            // selectionId → "DRAW" or FIFA code, per market
            Map<String, Map<Long, String>> marketSelections = new LinkedHashMap<>();
            Map<String, String> marketToMatchId = new LinkedHashMap<>();
            Map<String, GroupMatch> marketToMatch = new LinkedHashMap<>();
            List<String> matchedMarketIds = new ArrayList<>();

            for (var market : markets) {
                String marketId = market.path("marketId").asText();
                var runners = market.path("runners");

                Map<Long, String> selMap = new LinkedHashMap<>();
                Set<String> teamCodes = new LinkedHashSet<>();

                for (var runner : runners) {
                    String runnerName = runner.path("runnerName").asText("").trim();
                    long selectionId = runner.path("selectionId").asLong();

                    if (runnerName.equalsIgnoreCase("Draw")
                            || runnerName.equalsIgnoreCase("The Draw")) {
                        selMap.put(selectionId, "DRAW");
                        continue;
                    }
                    String fifaCode = BetfairNamesToCodes.BETFAIR_TO_FIFA.get(runnerName);
                    if (fifaCode != null) {
                        selMap.put(selectionId, fifaCode);
                        teamCodes.add(fifaCode);
                    }
                }

                if (teamCodes.size() == 2) {
                    List<String> codes = new ArrayList<>(teamCodes);
                    String pairKey = sortedPair(codes.get(0), codes.get(1));
                    String matchId = pairToMatchId.get(pairKey);
                    if (matchId != null) {
                        marketSelections.put(marketId, selMap);
                        marketToMatchId.put(marketId, matchId);
                        marketToMatch.put(marketId, groupMatches.get(matchId));
                        matchedMarketIds.add(marketId);
                    }
                }
            }

            log.info(" simulateGroupStageOdds: matched {} Betfair market(s) to group matches",
                    matchedMarketIds.size());

            // ── Batch-fetch market books (Betfair limit: 40 per call) ─────
            int batchSize = 40;
            for (int i = 0; i < matchedMarketIds.size(); i += batchSize) {
                List<String> batch = matchedMarketIds.subList(
                        i, Math.min(i + batchSize, matchedMarketIds.size()));
                String bookJson = marketClient.listMarketBook(sessionToken, batch);
                if (bookJson == null) continue;

                var books = objectMapper.readTree(bookJson);
                for (var book : books) {
                    String marketId = book.path("marketId").asText();
                    String matchId = marketToMatchId.get(marketId);
                    if (matchId == null) continue;

                    GroupMatch match = marketToMatch.get(marketId);
                    Map<Long, String> selMap = marketSelections.get(marketId);

                    Double team1Odds = null;
                    Double drawOdds = null;
                    Double team2Odds = null;

                    for (var runner : book.path("runners")) {
                        long selId = runner.path("selectionId").asLong();
                        String codeOrDraw = selMap.get(selId);
                        if (codeOrDraw == null) continue;

                        var backPrices = runner.path("ex").path("availableToBack");
                        if (backPrices.size() == 0) continue;
                        double bestBack = backPrices.get(0).path("price").asDouble();
                        if (bestBack <= 1.0) continue; // invalid / no-market

                        if ("DRAW".equals(codeOrDraw)) {
                            drawOdds = bestBack;
                        } else if (codeOrDraw.equals(match.getTeam1Code())) {
                            team1Odds = bestBack;
                        } else if (codeOrDraw.equals(match.getTeam2Code())) {
                            team2Odds = bestBack;
                        }
                    }

                    int[] score = simulateMatch(random, team1Odds, drawOdds, team2Odds);
                    results.put(matchId, score);

                    log.debug("  {} ({} vs {}): odds T1={} D={} T2={} → {}-{}",
                            matchId, match.getTeam1Code(), match.getTeam2Code(),
                            team1Odds, drawOdds, team2Odds, score[0], score[1]);
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
            for (var entry : groupMatches.entrySet()) {
                if (!results.containsKey(entry.getKey())) {
                    results.put(entry.getKey(), simulateWithFallback(random));
                }
            }
        }

        return results;
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
}
