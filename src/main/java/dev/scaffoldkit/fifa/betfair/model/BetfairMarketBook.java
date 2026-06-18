package dev.scaffoldkit.fifa.betfair.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Market book entry from listMarketBook API with current odds.
 *
 * @param marketId               market ID
 * @param isMarketDataDelayed    whether data is delayed
 * @param status                 market status (e.g., "OPEN", "CLOSED")
 * @param betDelay               bet delay in seconds
 * @param bspReconciled          whether BSP is reconciled
 * @param complete               whether book is complete
 * @param inplay                 whether market is in-play
 * @param numberOfWinners        number of potential winners
 * @param numberOfRunners        total number of runners
 * @param numberOfActiveRunners  number of active runners
 * @param lastMatchTime          timestamp of last match
 * @param totalMatched           total matched amount
 * @param totalAvailable         total amount available
 * @param crossMatching          whether cross-matching is enabled
 * @param runnersVoidable        whether runners are voidable
 * @param version                market version
 * @param runners                list of runners with prices
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairMarketBookDeserializer.class)
public record BetfairMarketBook(
        String marketId,
        boolean isMarketDataDelayed,
        String status,
        int betDelay,
        boolean bspReconciled,
        boolean complete,
        boolean inplay,
        int numberOfWinners,
        int numberOfRunners,
        int numberOfActiveRunners,
        String lastMatchTime,
        double totalMatched,
        double totalAvailable,
        boolean crossMatching,
        boolean runnersVoidable,
        long version,
        List<BetfairRunnerBook> runners
) {
}