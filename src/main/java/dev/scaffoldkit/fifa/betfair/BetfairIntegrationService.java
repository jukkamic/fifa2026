package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
}