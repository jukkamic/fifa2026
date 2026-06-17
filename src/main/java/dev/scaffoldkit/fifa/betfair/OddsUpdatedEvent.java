package dev.scaffoldkit.fifa.betfair;

/**
 * Spring application event published whenever Betfair odds data is updated
 * (e.g. by the scheduled snapshot or admin upload).
 *
 * <p>Listeners can use this to invalidate caches that depend on odds data,
 * such as the {@code matchesEnriched} flag in
 * {@link dev.scaffoldkit.fifa.controller.TournamentController}.
 *
 * @param source a short description of what triggered the update (for logging)
 */
public record OddsUpdatedEvent(String source) {
}