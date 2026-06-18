package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Runner information from market book with exchange prices.
 *
 * @param selectionId      selection ID
 * @param handicap         handicap value
 * @param status           runner status
 * @param lastPriceTraded  last price traded
 * @param totalMatched     total matched amount
 * @param ex               exchange prices
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairRunnerBookDeserializer.class)
public record BetfairRunnerBook(
        long selectionId,
        double handicap,
        String status,
        double lastPriceTraded,
        double totalMatched,
        BetfairExchangePrices ex
) {
}