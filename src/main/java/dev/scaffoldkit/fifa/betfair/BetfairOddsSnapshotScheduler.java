package dev.scaffoldkit.fifa.betfair;

import dev.scaffoldkit.fifa.service.AppEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically snapshots Betfair odds to the local fallback file.
 *
 * <p>Active only in non-{@code prod} profiles (where the live Betfair API is
 * reachable). Runs every 30 minutes with an initial 5-minute delay so the
 * {@link BetfairIntegrationService} startup routine has time to authenticate
 * first.
 */
@Component
@Profile("!prod")
class BetfairOddsSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BetfairOddsSnapshotScheduler.class);

    /** Interval between the end of one snapshot and the start of the next. */
    private static final long FIXED_DELAY_MS = 30 * 60 * 1000L;

    /** Delay before the first scheduled snapshot (lets startup init settle). */
    private static final long INITIAL_DELAY_MS = 5 * 60 * 1000L;

    private final BetfairIntegrationService betfairService;
    private final AppEventService appEvents;

    BetfairOddsSnapshotScheduler(BetfairIntegrationService betfairService, AppEventService appEvents) {
        this.betfairService = betfairService;
        this.appEvents = appEvents;
    }

    /**
     * Calls {@link BetfairIntegrationService#snapshotOddsLocally()} on a fixed
     * delay. Exceptions are caught and logged so that one failed run does not
     * prevent subsequent executions.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    void snapshotOddsEvery30Minutes() {
        log.info("Scheduled Betfair odds snapshot starting...");
        try {
            betfairService.snapshotOddsLocally();
            appEvents.emitInfo("BetfairUpdate", "Scheduled odds snapshot completed successfully.");
        } catch (Exception e) {
            log.error("Scheduled Betfair odds snapshot failed", e);
            appEvents.emitWarning("Betfair",
                    "Scheduled odds snapshot failed: " + e.getMessage());
        }
    }
}