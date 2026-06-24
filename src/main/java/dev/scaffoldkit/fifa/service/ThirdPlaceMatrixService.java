package dev.scaffoldkit.fifa.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Implements the official 2026 FIFA World Cup third-place advancement rules
 * for the Round of 32 bracket.
 *
 * <p>From 12 groups (A–L), 8 third-place teams advance. The 8 groups whose
 * third-place teams advance form an 8-letter key (sorted alphabetically).
 * This key selects a precomputed opponent mapping from the official Annex C
 * table, which is loaded once at startup from {@code annex_c.json} and held
 * in an in-memory cache for O(1) lookups.
 *
 * <p>Each entry in the table maps a third-place GROUP to the group winner it
 * faces in the Round of 32 (e.g. {@code "E" -> "A"} means the third-place team
 * from group E plays the winner of group A).
 */
@Service
public class ThirdPlaceMatrixService {

    private static final Logger log = LoggerFactory.getLogger(ThirdPlaceMatrixService.class);

    private final ObjectMapper objectMapper;
    private final Resource annexCResource;

    /**
     * In-memory cache of the Annex C matrix: key = the 8 advancing
     * third-place group letters sorted alphabetically (e.g. "EFGHIJKL"),
     * value = mapping of third-place group -> winner group.
     */
    private final Map<String, Map<String, String>> matrixCache = new HashMap<>();

    public ThirdPlaceMatrixService(ObjectMapper objectMapper,
                                   @Value("classpath:annex_c.json") Resource annexCResource) {
        this.objectMapper = objectMapper;
        this.annexCResource = annexCResource;
    }

    /**
     * Loads the Annex C matrix from the classpath JSON file at startup.
     */
    @PostConstruct
    public void init() {
        try (InputStream is = annexCResource.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Map<String, String>> loaded = objectMapper.readValue(
                    json, new TypeReference<Map<String, Map<String, String>>>() {});
            matrixCache.putAll(loaded);
            log.info("Loaded Annex C third-place matrix: {} combinations", matrixCache.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Annex C matrix from annex_c.json", e);
        }
    }

    /**
     * Looks up the third-place opponent mapping for the 8 advancing
     * third-place teams.
     *
     * <p>The group letters of the 8 advancing third-place teams are sorted
     * strictly alphabetically and concatenated into a single 8-character key
     * (e.g. "EFGHIJKL"), which is then used for an O(1) lookup against the
     * cached Annex C matrix.
     *
     * @param advancingThirdPlaceGroups the 8 groups whose third-place teams
     *                                  advance to the Round of 32
     * @return mapping of third-place group -> winner group, as defined by
     *         Annex C for this combination of advancing groups
     */
    public Map<String, String> solve(Collection<String> advancingThirdPlaceGroups) {
        if (advancingThirdPlaceGroups == null || advancingThirdPlaceGroups.size() != 8) {
            throw new IllegalArgumentException(
                    "Exactly 8 advancing third-place groups are required, got: "
                            + (advancingThirdPlaceGroups == null ? "null" : advancingThirdPlaceGroups.size()));
        }

        List<String> sorted = new ArrayList<>(advancingThirdPlaceGroups);
        Collections.sort(sorted);
        String key = String.join("", sorted);

        Map<String, String> mapping = matrixCache.get(key);
        if (mapping == null) {
            throw new IllegalStateException(
                    "No Annex C entry found for third-place key: " + key);
        }

        return mapping;
    }
}