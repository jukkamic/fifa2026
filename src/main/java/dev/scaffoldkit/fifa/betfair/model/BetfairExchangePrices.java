package dev.scaffoldkit.fifa.betfair.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Exchange prices (back/lay/volume) for a runner.
 *
 * @param availableToBack  best back prices available
 * @param availableToLay   best lay prices available
 * @param tradedVolume     traded volume prices
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairExchangePricesDeserializer.class)
public record BetfairExchangePrices(
        List<BetfairPriceSize> availableToBack,
        List<BetfairPriceSize> availableToLay,
        List<BetfairPriceSize> tradedVolume
) {
}