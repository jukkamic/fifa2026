package dev.scaffoldkit.fifa.controller;

import dev.scaffoldkit.fifa.betfair.BetfairIntegrationService;
import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.model.GroupStanding;
import dev.scaffoldkit.fifa.model.KnockoutMatch;
import dev.scaffoldkit.fifa.model.Team;
import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.service.ActualResultsService;
import dev.scaffoldkit.fifa.service.AppEventService;
import dev.scaffoldkit.fifa.service.BracketService;
import dev.scaffoldkit.fifa.service.GroupStageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for the FIFA 2026 World Cup tournament.
 *
 * Endpoints:
 * GET /api/teams — all 48 teams
 * GET /api/groups — all 12 groups with team codes
 * GET /api/groups/{group} — single group details + standings + matches
 * POST /api/groups/{matchId}/score — set group match score
 * GET /api/standings — all group standings
 * GET /api/advancement — which teams advance (winners, runners-up, 3rd)
 * GET /api/bracket — full knockout bracket
 * POST /api/bracket/seed — seed bracket from group results
 * POST /api/bracket/{matchId}/score — set knockout score
 * POST /api/reset — reset everything
 */
@RestController
@RequestMapping("/api")
public class TournamentController {

    private final String adminEmail;
    private final String devAdminEmail;
    private final Path fallbackOddsPath;
    private final GroupStageService groupStageService;
    private final BracketService bracketService;
    private final BetfairIntegrationService betfairService;
    private final AppEventService appEvents;
    private final ActualResultsService actualResultsService;

    public TournamentController(
            @Value("${app.admin.email:jukkamic@gmail.com}") String adminEmail,
            @Value("${app.admin.dev-email:testuser@example.com}") String devAdminEmail,
            GroupStageService groupStageService,
            BracketService bracketService,
            BetfairIntegrationService betfairService,
            AppEventService appEvents,
            ActualResultsService actualResultsService) {
        this.adminEmail = adminEmail;
        this.devAdminEmail = devAdminEmail;
        this.fallbackOddsPath = betfairService.getFallbackOddsPath();
        this.groupStageService = groupStageService;
        this.bracketService = bracketService;
        this.betfairService = betfairService;
        this.appEvents = appEvents;
        this.actualResultsService = actualResultsService;
    }

    // ── Teams ────────────────────────────────────────────────────────────

    @GetMapping("/teams")
    public Map<String, Map<String, String>> getTeams() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (var entry : groupStageService.getAllTeams().entrySet()) {
            Team t = entry.getValue();
            Map<String, String> info = new LinkedHashMap<>();
            info.put("code", t.getCode());
            info.put("name", t.getName());
            info.put("group", t.getGroup());
            info.put("flag", t.getFlag());
            result.put(entry.getKey(), info);
        }
        return result;
    }

    // ── Groups ───────────────────────────────────────────────────────────

    @GetMapping("/groups")
    public Map<String, Object> getGroups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groupStageService.getGroups());
        return result;
    }

    @GetMapping("/groups/{group}")
    public Map<String, Object> getGroup(@PathVariable String group) {
        ensureMatchesEnriched();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("teams", groupStageService.getGroups().get(group));

        // Standings
        List<Map<String, Object>> standingsList = new ArrayList<>();
        for (GroupStanding gs : groupStageService.getSortedStandings(group)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("teamCode", gs.getTeamCode());
            s.put("played", gs.getPlayed());
            s.put("points", gs.getPoints());
            s.put("goalsFor", gs.getGoalsFor());
            s.put("goalsAgainst", gs.getGoalsAgainst());
            s.put("goalDifference", gs.getGoalDifference());
            standingsList.add(s);
        }
        result.put("standings", standingsList);

        // Matches — sorted chronologically by matchDate
        List<Map<String, Object>> matchesList = new ArrayList<>();
        groupStageService.getMatchesForGroup(group).stream()
                .sorted(this::compareByMatchDate)
                .forEach(gm -> matchesList.add(formatGroupMatch(gm)));
        result.put("matches", matchesList);

        return result;
    }

    // ── Group Match Score ────────────────────────────────────────────────

    @PostMapping("/groups/{matchId}/score")
    public ResponseEntity<Map<String, Object>> setGroupScore(
            @PathVariable String matchId,
            @RequestBody Map<String, Integer> body) {
        Integer score1 = body.get("score1");
        Integer score2 = body.get("score2");

        groupStageService.setGroupMatchScore(matchId, score1, score2);

        // Return updated standings and advancement info
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("advancement", getAdvancementInternal());
        return ResponseEntity.ok(result);
    }

    // ── Standings ────────────────────────────────────────────────────────

    @GetMapping("/standings")
    public Map<String, Object> getStandings() {
        Map<String, List<Map<String, Object>>> allStandings = new LinkedHashMap<>();
        for (String group : List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")) {
            List<Map<String, Object>> standingsList = new ArrayList<>();
            for (GroupStanding gs : groupStageService.getSortedStandings(group)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("teamCode", gs.getTeamCode());
                s.put("played", gs.getPlayed());
                s.put("points", gs.getPoints());
                s.put("goalsFor", gs.getGoalsFor());
                s.put("goalsAgainst", gs.getGoalsAgainst());
                s.put("goalDifference", gs.getGoalDifference());
                standingsList.add(s);
            }
            allStandings.put(group, standingsList);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("standings", allStandings);
        return result;
    }

    // ── Advancement ──────────────────────────────────────────────────────

    @GetMapping("/advancement")
    public Map<String, Object> getAdvancement() {
        return getAdvancementInternal();
    }

    private Map<String, Object> getAdvancementInternal() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("winners", groupStageService.getAllGroupWinners());
        result.put("runnersUp", groupStageService.getAllRunnersUp());
        result.put("thirdPlaces", groupStageService.getAllThirdPlaces());
        result.put("bestThirdGroups", groupStageService.getBestThirdPlaceGroups());
        result.put("bestThirdTeamCodes", groupStageService.getBestThirdPlaceTeamCodes());

        // Eliminated groups
        List<String> all = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L");
        List<String> best = groupStageService.getBestThirdPlaceGroups();
        List<String> eliminated = all.stream()
                .filter(g -> !best.contains(g))
                .collect(Collectors.toList());
        result.put("eliminatedGroups", eliminated);

        return result;
    }

    // ── Bracket ──────────────────────────────────────────────────────────

    @GetMapping("/bracket")
    public Map<String, Object> getBracket() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> matchList = new ArrayList<>();
        for (KnockoutMatch km : bracketService.getMatches().values()) {
            matchList.add(formatKnockoutMatch(km));
        }
        result.put("matches", matchList);

        // Final info
        KnockoutMatch finalMatch = bracketService.getFinalMatch();
        if (finalMatch != null && finalMatch.hasResult()) {
            String winner = finalMatch.getWinnerCode();
            if (winner != null) {
                result.put("champion", winner);
            }
        }

        return result;
    }

    @PostMapping("/bracket/seed")
    public Map<String, Object> seedBracket() {
        bracketService.seedBracket();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Bracket seeded from group stage results");
        return result;
    }

    @PostMapping("/bracket/{matchId}/score")
    public ResponseEntity<Map<String, Object>> setKnockoutScore(
            @PathVariable int matchId,
            @RequestBody Map<String, Integer> body) {
        Integer score1 = body.get("score1");
        Integer score2 = body.get("score2");

        bracketService.setKnockoutScore(matchId, score1, score2);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("match", formatKnockoutMatch(bracketService.getMatch(matchId)));

        // Check for champion
        KnockoutMatch finalMatch = bracketService.getFinalMatch();
        if (finalMatch != null && finalMatch.hasResult()) {
            String winner = finalMatch.getWinnerCode();
            if (winner != null) {
                result.put("champion", winner);
            }
        }

        return ResponseEntity.ok(result);
    }

    // ── Snapshot Odds ────────────────────────────────────────────────────

    @GetMapping("/admin/snapshot-odds")
    @Profile("!prod")
    public ResponseEntity<Map<String, Object>> snapshotOdds() {
        try {
            betfairService.snapshotOddsLocally();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "Odds snapshot created");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            appEvents.emitError("Betfair",
                    "Failed to snapshot odds: " + e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Failed to snapshot odds: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ── Fallback Odds Timestamp ──────────────────────────────────────────

    private static final DateTimeFormatter FINNISH_FORMATTER = DateTimeFormatter.ofPattern("d.M. HH:mm:ss")
            .withZone(ZoneId.of("Europe/Helsinki"));

    @GetMapping("/fallback-odds-timestamp")
    public Map<String, Object> getFallbackOdsTimestamp() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Primary: read snapshotTimestamp embedded in the JSON file
            String snapshotTimestamp = readSnapshotTimestampFromJson();
            if (snapshotTimestamp != null) {
                Instant instant = Instant.parse(snapshotTimestamp);
                String formatted = FINNISH_FORMATTER.format(instant);
                result.put("timestamp", formatted);
                return result;
            }

            // Fallback: try filesystem lastModified (dev mode source file)
            long lastModified = 0;
            var sourceFile = new File("src/main/resources/fallback-odds.json");
            if (sourceFile.exists()) {
                lastModified = sourceFile.lastModified();
            }

            if (lastModified > 0) {
                String formatted = FINNISH_FORMATTER.format(Instant.ofEpochMilli(lastModified));
                result.put("timestamp", formatted);
            } else {
                result.put("timestamp", (String) null);
            }
        } catch (Exception e) {
            result.put("timestamp", (String) null);
        }
        return result;
    }

    /**
     * Reads the {@code snapshotTimestamp} field from {@code fallback-odds.json}.
     * Tries the persistent filesystem location first (writable by admin upload),
     * then falls back to the classpath resource (bundled in JAR).
     *
     * @return the ISO-8601 timestamp string, or {@code null} if not found
     */
    private String readSnapshotTimestampFromJson() {
        // 1. Try filesystem (persistent volume)
        if (Files.exists(fallbackOddsPath)) {
            try {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(fallbackOddsPath.toFile());
                var node = tree.path("snapshotTimestamp");
                if (!node.isMissingNode() && !node.asText().isEmpty()) {
                    return node.asText();
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Fallback: classpath resource (bundled in JAR)
        try {
            var resource = new ClassPathResource("fallback-odds.json");
            try (var is = resource.getInputStream()) {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(is);
                var node = tree.path("snapshotTimestamp");
                if (!node.isMissingNode() && !node.asText().isEmpty()) {
                    return node.asText();
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    // ── Admin: Upload Fallback Odds ──────────────────────────────────────

    /**
     * Accepts a raw Betfair JSON string and writes it to the persistent
     * fallback-odds.json file. Requires admin privileges (same security
     * check as the Lock Score feature).
     */
    @PostMapping(value = "/admin/odds/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFallbackOdds(
            @RequestBody String jsonBody,
            @AuthenticationPrincipal UserProfile profile) {
        if (!isAdmin(profile.getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (jsonBody == null || jsonBody.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Request body is empty");
            return ResponseEntity.badRequest().body(err);
        }

        // Validate that it's valid JSON
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(jsonBody);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Invalid JSON: " + e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }

        try {
            // Ensure parent directory exists
            Path parent = fallbackOddsPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(fallbackOddsPath, jsonBody, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            appEvents.emitInfo("BetfairUpdate",
                    "Fallback odds updated by admin (" + profile.getEmail() + ").");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Fallback odds saved to " + fallbackOddsPath.toAbsolutePath());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            appEvents.emitError("System",
                    "Failed to save fallback odds: " + e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Failed to write file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ── App Events ───────────────────────────────────────────────────────

    @GetMapping("/events")
    public Map<String, Object> getEvents() {
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (var event : appEvents.getEvents()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("timestamp", event.timestamp().toString());
            e.put("type", event.type());
            e.put("category", event.category());
            e.put("message", event.message());
            eventList.add(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", eventList);
        return result;
    }

    // ── Reset ────────────────────────────────────────────────────────────

    @PostMapping("/reset")
    public Map<String, Object> resetAll() {
        groupStageService.resetAll();
        getOverwritten();

        bracketService.resetAndReseed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "All data reset");
        return result;
    }

    // ── Group Matches (all) ──────────────────────────────────────────────

    /**
     * Tracks whether matches have been enriched with Betfair metadata
     * (matchDate + odds) during this request cycle.
     */
    private volatile boolean matchesEnriched = false;

    /**
     * Enriches group matches with Betfair metadata (matchDate + odds) once.
     * Subsequent calls are no-ops. Re-enrich on Betfair simulation.
     */
    private void ensureMatchesEnriched() {
        if (!matchesEnriched) {
            try {
                betfairService.enrichMatchesWithBetfairData(groupStageService.getGroupMatches());
                matchesEnriched = true;
            } catch (Exception e) {
                // Enrichment failure is non-fatal — matches will just lack dates/odds
            }
        }
    }

    @GetMapping("/group-matches")
    public Map<String, Object> getAllGroupMatches() {
        ensureMatchesEnriched();

        List<Map<String, Object>> matchList = new ArrayList<>();
        groupStageService.getGroupMatches().values().stream()
                .sorted(this::compareByMatchDate)
                .forEach(gm -> matchList.add(formatGroupMatch(gm)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matchList);
        return result;
    }

    // ── Admin: Lock/Unlock Actual Results ────────────────────────────────

    @PostMapping("/admin/lock-score/{matchId}")
    public ResponseEntity<Map<String, Object>> lockScore(
            @PathVariable String matchId,
            @RequestBody Map<String, Integer> body,
            @AuthenticationPrincipal UserProfile profile) {
        if (!isAdmin(profile.getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Integer score1 = body.get("score1");
        Integer score2 = body.get("score2");
        if (score1 == null || score2 == null) {
            return ResponseEntity.badRequest().build();
        }
        actualResultsService.lockScore(matchId, score1, score2);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Match %s locked with score %d-%d".formatted(matchId, score1, score2));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/admin/lock-score/{matchId}")
    public ResponseEntity<Map<String, Object>> unlockScore(
            @PathVariable String matchId,
            @AuthenticationPrincipal UserProfile profile) {
        if (!isAdmin(profile.getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        actualResultsService.unlockScore(matchId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Match %s unlocked".formatted(matchId));
        return ResponseEntity.ok(result);
    }

    // ── Betfair Simulation ───────────────────────────────────────────────

    @PostMapping("/betfair/simulate-groups")
    public ResponseEntity<Map<String, Object>> simulateGroupsWithBetfair() {
        Map<String, GroupMatch> groupMatches = groupStageService.getGroupMatches();
        Map<String, int[]> simulated = betfairService.simulateGroupStageOdds(groupMatches);

        // Apply all simulated scores
        int updated = 0;
        for (var entry : simulated.entrySet()) {
            groupStageService.setGroupMatchScore(
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            updated++;
        }

        int overwritten = getOverwritten();

        // Re-enrich matches after simulation (odds may have changed)
        matchesEnriched = false;
        ensureMatchesEnriched();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("matchesSimulated", updated);
        result.put("lockedOverwritten", overwritten);
        result.put("message", "Group stage simulated from Betfair odds (%d matches, %d locked results applied)"
                .formatted(updated, overwritten));
        return ResponseEntity.ok(result);
    }

    private int getOverwritten() {
        // Overwrite with locked actual results
        Map<String, int[]> locked = actualResultsService.getLockedScores();
        int overwritten = 0;
        for (var entry : locked.entrySet()) {
            groupStageService.setGroupMatchScore(
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            overwritten++;
        }
        return overwritten;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isAdmin(String email) {
        return adminEmail.equals(email) || devAdminEmail.equals(email);
    }

    private Map<String, Object> formatGroupMatch(GroupMatch gm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", gm.getId());
        m.put("group", gm.getGroup());
        m.put("team1", gm.getTeam1Code());
        m.put("team2", gm.getTeam2Code());
        m.put("score1", gm.getScore1());
        m.put("score2", gm.getScore2());
        if (gm.getMatchDate() != null) {
            m.put("matchDate", gm.getMatchDate());
        }
        if (gm.getOdds1() != null) {
            m.put("odds1", gm.getOdds1());
        }
        if (gm.getOddsDraw() != null) {
            m.put("oddsDraw", gm.getOddsDraw());
        }
        if (gm.getOdds2() != null) {
            m.put("odds2", gm.getOdds2());
        }
        return m;
    }

    /**
     * Compares two GroupMatches by matchDate for chronological sorting.
     * Matches without a date sort after those with a date.
     */
    private int compareByMatchDate(GroupMatch a, GroupMatch b) {
        String dateA = a.getMatchDate();
        String dateB = b.getMatchDate();
        if (dateA == null && dateB == null)
            return 0;
        if (dateA == null)
            return 1;
        if (dateB == null)
            return -1;
        return dateA.compareTo(dateB);
    }

    private Map<String, Object> formatKnockoutMatch(KnockoutMatch km) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", km.getId());
        m.put("round", km.getRound());
        m.put("side", km.getSide());
        m.put("matchIndex", km.getMatchIndex());
        m.put("team1", km.getTeam1Code());
        m.put("team2", km.getTeam2Code());
        m.put("score1", km.getScore1());
        m.put("score2", km.getScore2());
        m.put("nextMatchId", km.getNextMatchId());
        m.put("nextSlot", km.getNextSlot());
        if (km.hasResult() && km.getWinnerCode() != null) {
            m.put("winner", km.getWinnerCode());
        }
        return m;
    }
}