package dev.scaffoldkit.fifa.betfair.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Market catalogue entry from listMarketCatalogue API.
 *
 * @param marketId          market ID
 * @param marketName        market name (e.g., "Match Odds")
 * @param marketStartTime   market start time
 * @param totalMatched      total matched amount
 * @param runners           list of runners
 * @param eventType         event type (e.g., "Soccer")
 * @param competition       competition (e.g., "FIFA World Cup")
 * @param event             event (e.g., "Uzbekistan v Colombia")
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairMarketCatalogDeserializer.class)
public record BetfairMarketCatalog(
        String marketId,
        String marketName,
        String marketStartTime,
        double totalMatched,
        List<BetfairRunnerCatalog> runners,
        BetfairEventType eventType,
        BetfairCompetition competition,
        BetfairEvent event
) {
}