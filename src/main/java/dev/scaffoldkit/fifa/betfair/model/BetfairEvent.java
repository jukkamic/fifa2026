package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Betfair event (e.g., "Uzbekistan v Colombia").
 *
 * @param id       event ID
 * @param name     event name
 * @param timezone event timezone
 * @param openDate event open date
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairEventDeserializer.class)
public record BetfairEvent(
        String id,
        String name,
        String timezone,
        String openDate
) {
}