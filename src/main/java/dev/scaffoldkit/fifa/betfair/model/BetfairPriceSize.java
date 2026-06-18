package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Price and size for odds (e.g., back/lay prices).
 *
 * @param price price value
 * @param size  size (amount available)
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairPriceSizeDeserializer.class)
public record BetfairPriceSize(
        double price,
        double size
) {
}