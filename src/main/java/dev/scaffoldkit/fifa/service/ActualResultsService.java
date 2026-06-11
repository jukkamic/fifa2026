package dev.scaffoldkit.fifa.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages locked (actual) group match results that cannot be overwritten
 * by Betfair simulation. Persisted to a JSON file on every change.
 */
@Service
public class ActualResultsService {

    private static final Logger log = LoggerFactory.getLogger(ActualResultsService.class);

    private final ObjectMapper objectMapper;
    private final File dataFile;

    /** matchId → [score1, score2] */
    private Map<String, int[]> lockedScores = new LinkedHashMap<>();

    public ActualResultsService(ObjectMapper objectMapper,
                                @Value("${app.data.dir:./data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataFile = new File(dataDir, "actual-results.json");
    }

    @PostConstruct
    void init() {
        loadFromFile();
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Returns an unmodifiable view of the current locked scores. */
    public Map<String, int[]> getLockedScores() {
        return Collections.unmodifiableMap(lockedScores);
    }

    /** Locks a score for the given match and persists. */
    public void lockScore(String matchId, int score1, int score2) {
        lockedScores.put(matchId, new int[]{score1, score2});
        saveToFile();
        log.info("Locked actual result for match {}: {}-{}", matchId, score1, score2);
    }

    /** Removes the lock for the given match and persists. */
    public void unlockScore(String matchId) {
        if (lockedScores.remove(matchId) != null) {
            saveToFile();
            log.info("Unlocked actual result for match {}", matchId);
        }
    }

    /** Checks whether a given match has a locked score. */
    public boolean isLocked(String matchId) {
        return lockedScores.containsKey(matchId);
    }

    // ── Persistence ────────────────────────────────────────────────────

    private void loadFromFile() {
        if (!dataFile.exists()) {
            log.info("No actual-results file found at {}. Starting empty.", dataFile.getAbsolutePath());
            return;
        }
        try {
            Map<String, int[]> loaded = objectMapper.readValue(
                    dataFile, new TypeReference<LinkedHashMap<String, int[]>>() {});
            this.lockedScores = loaded != null ? loaded : new LinkedHashMap<>();
            log.info("Loaded {} locked actual results from {}", lockedScores.size(), dataFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to load actual-results from {}: {}. Starting empty.",
                    dataFile.getAbsolutePath(), e.getMessage());
            this.lockedScores = new LinkedHashMap<>();
        }
    }

    private void saveToFile() {
        try {
            // Ensure parent directory exists
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile, lockedScores);
        } catch (IOException e) {
            log.error("Failed to save actual-results to {}: {}", dataFile.getAbsolutePath(), e.getMessage());
        }
    }
}