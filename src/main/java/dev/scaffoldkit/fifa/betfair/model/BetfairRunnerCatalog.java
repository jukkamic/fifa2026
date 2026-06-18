package dev.scaffoldkit.fifa.betfair.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Runner information from market catalogue.
 *
 * @param selectionId   selection ID
 * @param runnerName    runner name
 * @param handicap      handicap value
 * @param sortPriority  sort priority
 */
@JsonDeserialize(using = dev.scaffoldkit.fifa.betfair.deserializer.BetfairRunnerCatalogDeserializer.class)
public record BetfairRunnerCatalog(
        long selectionId,
        String runnerName,
        double handicap,
        int sortPriority
) {
}