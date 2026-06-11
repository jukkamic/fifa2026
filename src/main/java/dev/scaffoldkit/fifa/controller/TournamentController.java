package dev.scaffoldkit.fifa.controller;

import dev.scaffoldkit.fifa.betfair.BetfairIntegrationService;
import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.model.GroupStanding;
import dev.scaffoldkit.fifa.model.KnockoutMatch;
import dev.scaffoldkit.fifa.model.Team;
import dev.scaffoldkit.fifa.service.AppEventService;
import dev.scaffoldkit.fifa.service.BracketService;
import dev.scaffoldkit.fifa.service.GroupStageService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for the FIFA 2026 World Cup tournament.
 *
 * Endpoints:
 *   GET  /api/teams           — all 48 teams
 *   GET  /api/groups          — all 12 groups with team codes
 *   GET  /api/groups/{group}  — single group details + standings + matches
 *   POST /api/groups/{matchId}/score  — set group match score
 *   GET  /api/standings       — all group standings
 *   GET  /api/advancement     — which teams advance (winners, runners-up, 3rd)
 *   GET  /api/bracket         — full knockout bracket
 *   POST /api/bracket/seed    — seed bracket from group results
 *   POST /api/bracket/{matchId}/score — set knockout score
 *   POST /api/reset           — reset everything
 */
@RestController
@RequestMapping("/api")
public class TournamentController {

    private final GroupStageService groupStageService;
    private final BracketService bracketService;
    private final BetfairIntegrationService betfairService;
    private final AppEventService appEvents;

    public TournamentController(GroupStageService groupStageService,
                                BracketService bracketService,
                                BetfairIntegrationService betfairService,
                                AppEventService appEvents) {
        this.groupStageService = groupStageService;
        this.bracketService = bracketService;
        this.betfairService = betfairService;
        this.appEvents = appEvents;
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

        // Matches
        List<Map<String, Object>> matchesList = new ArrayList<>();
        for (GroupMatch gm : groupStageService.getMatchesForGroup(group)) {
            matchesList.add(formatGroupMatch(gm));
        }
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
        for (String group : List.of("A","B","C","D","E","F","G","H","I","J","K","L")) {
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
        List<String> all = List.of("A","B","C","D","E","F","G","H","I","J","K","L");
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
        betfairService.snapshotOddsLocally();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Odds snapshot created");
        return ResponseEntity.ok(result);
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
        bracketService.resetAndReseed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "All data reset");
        return result;
    }

    // ── Group Matches (all) ──────────────────────────────────────────────

    @GetMapping("/group-matches")
    public Map<String, Object> getAllGroupMatches() {
        List<Map<String, Object>> matchList = new ArrayList<>();
        for (GroupMatch gm : groupStageService.getGroupMatches().values()) {
            matchList.add(formatGroupMatch(gm));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matchList);
        return result;
    }

    // ── Betfair Simulation ───────────────────────────────────────────────

    @PostMapping("/betfair/simulate-groups")
    public ResponseEntity<Map<String, Object>> simulateGroupsWithBetfair() {
        Map<String, GroupMatch> groupMatches = groupStageService.getGroupMatches();
        Map<String, int[]> simulated = betfairService.simulateGroupStageOdds(groupMatches);

        int updated = 0;
        for (var entry : simulated.entrySet()) {
            groupStageService.setGroupMatchScore(
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            updated++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("matchesSimulated", updated);
        result.put("message", "Group stage simulated from Betfair odds (%d matches)".formatted(updated));
        return ResponseEntity.ok(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Object> formatGroupMatch(GroupMatch gm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", gm.getId());
        m.put("group", gm.getGroup());
        m.put("team1", gm.getTeam1Code());
        m.put("team2", gm.getTeam2Code());
        m.put("score1", gm.getScore1());
        m.put("score2", gm.getScore2());
        return m;
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