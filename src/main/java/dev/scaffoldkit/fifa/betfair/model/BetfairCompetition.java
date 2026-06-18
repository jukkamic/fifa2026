package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Betfair competition (e.g., "FIFA World Cup").
 *
 * @param id   competition ID (e.g., "12469077")
 * @param name competition name (e.g., "FIFA World Cup")
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairCompetitionDeserializer.class)
public record BetfairCompetition(
        String id,
        String name
) {
}