package dev.scaffoldkit.fifa.betfair.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Combined odds snapshot (catalogue + books + timestamp) stored in fallback-odds.json.
 *
 * @param catalogue          list of market catalogue entries
 * @param books              list of market book entries
 * @param snapshotTimestamp  timestamp when snapshot was taken
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairOddsSnapshotDeserializer.class)
public record BetfairOddsSnapshot(
        List<BetfairMarketCatalog> catalogue,
        List<BetfairMarketBook> books,
        String snapshotTimestamp
) {
}