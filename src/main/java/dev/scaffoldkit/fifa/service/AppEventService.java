package dev.scaffoldkit.fifa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory event bus that collects application events (errors, warnings, info)
 * for display to the user via the REST API.
 *
 * <p>Events are kept in a bounded ring buffer (max {@value #MAX_EVENTS} entries).
 * They survive for the lifetime of the application (lost on restart).
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code ERROR} — Something went wrong that the user should know about</li>
 *   <li>{@code WARNING} — A potential issue, but the system recovered</li>
 *   <li>{@code INFO} — Informational notice, e.g. a fallback was used</li>
 * </ul>
 */
@Service
public class AppEventService {

    private static final Logger log = LoggerFactory.getLogger(AppEventService.class);
    private static final int MAX_EVENTS = 50;

    private final List<AppEvent> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * Records a new application event.
     *
     * @param type     one of ERROR, WARNING, INFO
     * @param category a short label like "Betfair", "Database", "System"
     * @param message  a user-friendly description (no stack traces)
     */
    public void emit(String type, String category, String message) {
        AppEvent event = new AppEvent(
                Instant.now(), type, category, message
        );
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        log.info("AppEvent [{}] {}: {}", type, category, message);
    }

    /** Convenience: emit an ERROR event. */
    public void emitError(String category, String message) {
        emit("ERROR", category, message);
    }

    /** Convenience: emit a WARNING event. */
    public void emitWarning(String category, String message) {
        emit("WARNING", category, message);
    }

    /** Convenience: emit an INFO event. */
    public void emitInfo(String category, String message) {
        emit("INFO", category, message);
    }

    /**
     * Returns a snapshot of all recorded events (oldest first).
     */
    public List<AppEvent> getEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * Returns events recorded after the given instant (exclusive).
     */
    public List<AppEvent> getEventsAfter(Instant since) {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.timestamp().isAfter(since))
                    .toList();
        }
    }

    /**
     * Immutable application event record.
     */
    public record AppEvent(
            Instant timestamp,
            String type,
            String category,
            String message
    ) {}
}