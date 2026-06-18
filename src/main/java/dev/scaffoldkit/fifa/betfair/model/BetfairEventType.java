package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Betfair event type (e.g., "Soccer").
 *
 * @param id   event type ID (e.g., "1" for soccer)
 * @param name event type name (e.g., "Soccer")
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairEventTypeDeserializer.class)
public record BetfairEventType(
        String id,
        String name
) {
}