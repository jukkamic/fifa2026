package dev.scaffoldkit.fifa.service;

import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.model.GroupStanding;
import dev.scaffoldkit.fifa.model.Team;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the 12 groups (A–L), each with 4 teams playing round-robin (6 matches).
 * Tracks standings, computes advancement (top 2 + 8 best 3rd-place teams).
 */
@Service
public class GroupStageService {

    private static final String[] GROUP_NAMES = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"
    };

    /** All 48 teams keyed by FIFA code. */
    private final Map<String, Team> teams = new LinkedHashMap<>();

    /** Teams in each group: groupLetter → ordered list of 4 team codes. */
    private final Map<String, List<String>> groups = new LinkedHashMap<>();

    /** All group matches keyed by match ID (e.g. "A1", "A2", … "L6"). */
    private final Map<String, GroupMatch> groupMatches = new LinkedHashMap<>();

    /** Standings per group: groupLetter → teamCode → standing. */
    private final Map<String, Map<String, GroupStanding>> standings = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        initTeams();
        initGroupMatches();
        initStandings();
    }

    // ── Team Initialization ──────────────────────────────────────────────

    private void addTeam(String code, String name, String group, String flag) {
        Team team = new Team(code, name, group, flag);
        teams.put(code, team);
        groups.computeIfAbsent(group, k -> new ArrayList<>()).add(code);
    }

    private void initTeams() {
        // Group A
        addTeam("MEX", "Mexico",              "A", "mx");
        addTeam("RSA", "South Africa",        "A", "za");
        addTeam("KOR", "South Korea",         "A", "kr");
        addTeam("CZE", "Czech Republic",      "A", "cz");
        // Group B
        addTeam("CAN", "Canada",              "B", "ca");
        addTeam("BIH", "Bosnia and Herzegovina","B","ba");
        addTeam("QAT", "Qatar",               "B", "qa");
        addTeam("SUI", "Switzerland",         "B", "ch");
        // Group C
        addTeam("BRA", "Brazil",              "C", "br");
        addTeam("MAR", "Morocco",             "C", "ma");
        addTeam("HAI", "Haiti",               "C", "ht");
        addTeam("SCO", "Scotland",            "C", "gb-sct");
        // Group D
        addTeam("USA", "United States",       "D", "us");
        addTeam("PAR", "Paraguay",            "D", "py");
        addTeam("AUS", "Australia",           "D", "au");
        addTeam("TUR", "Türkiye",             "D", "tr");
        // Group E
        addTeam("GER", "Germany",             "E", "de");
        addTeam("CUW", "Curaçao",             "E", "cw");
        addTeam("CIV", "Ivory Coast",         "E", "ci");
        addTeam("ECU", "Ecuador",             "E", "ec");
        // Group F
        addTeam("NED", "Netherlands",         "F", "nl");
        addTeam("JPN", "Japan",               "F", "jp");
        addTeam("SWE", "Sweden",              "F", "se");
        addTeam("TUN", "Tunisia",             "F", "tn");
        // Group G
        addTeam("BEL", "Belgium",             "G", "be");
        addTeam("EGY", "Egypt",               "G", "eg");
        addTeam("IRN", "Iran",                "G", "ir");
        addTeam("NZL", "New Zealand",         "G", "nz");
        // Group H
        addTeam("ESP", "Spain",               "H", "es");
        addTeam("CPV", "Cabo Verde",          "H", "cv");
        addTeam("KSA", "Saudi Arabia",        "H", "sa");
        addTeam("URU", "Uruguay",             "H", "uy");
        // Group I
        addTeam("FRA", "France",              "I", "fr");
        addTeam("SEN", "Senegal",             "I", "sn");
        addTeam("IRQ", "Iraq",                "I", "iq");
        addTeam("NOR", "Norway",              "I", "no");
        // Group J
        addTeam("ARG", "Argentina",           "J", "ar");
        addTeam("ALG", "Algeria",             "J", "dz");
        addTeam("AUT", "Austria",             "J", "at");
        addTeam("JOR", "Jordan",              "J", "jo");
        // Group K
        addTeam("POR", "Portugal",            "K", "pt");
        addTeam("COD", "DR Congo",            "K", "cd");
        addTeam("UZB", "Uzbekistan",          "K", "uz");
        addTeam("COL", "Colombia",            "K", "co");
        // Group L
        addTeam("ENG", "England",             "L", "gb-eng");
        addTeam("CRO", "Croatia",             "L", "hr");
        addTeam("GHA", "Ghana",               "L", "gh");
        addTeam("PAN", "Panama",              "L", "pa");
    }

    // ── Group Match Initialization (round-robin) ────────────────────────

    private void initGroupMatches() {
        for (String group : GROUP_NAMES) {
            List<String> t = groups.get(group);
            // 6 matches per group: 0v1, 0v2, 0v3, 1v2, 1v3, 2v3
            createGroupMatch(group, 1, t.get(0), t.get(1));
            createGroupMatch(group, 2, t.get(0), t.get(2));
            createGroupMatch(group, 3, t.get(0), t.get(3));
            createGroupMatch(group, 4, t.get(1), t.get(2));
            createGroupMatch(group, 5, t.get(1), t.get(3));
            createGroupMatch(group, 6, t.get(2), t.get(3));
        }
    }

    private void createGroupMatch(String group, int num, String team1, String team2) {
        String id = group + num;
        groupMatches.put(id, new GroupMatch(id, group, team1, team2));
    }

    private void initStandings() {
        for (String group : GROUP_NAMES) {
            Map<String, GroupStanding> groupStandings = new LinkedHashMap<>();
            for (String teamCode : groups.get(group)) {
                groupStandings.put(teamCode, new GroupStanding(teamCode));
            }
            standings.put(group, groupStandings);
        }
    }

    // ── Recalculate Standings ────────────────────────────────────────────

    /**
     * Recalculates all standings from scratch based on current match results.
     */
    public void recalculateStandings() {
        // Reset all standings
        for (Map<String, GroupStanding> gs : standings.values()) {
            for (GroupStanding s : gs.values()) {
                // Re-create to reset
            }
        }
        // Re-init standings
        standings.clear();
        initStandings();

        // Apply all results
        for (GroupMatch match : groupMatches.values()) {
            if (match.hasResult()) {
                applyResult(match);
            }
        }
    }

    private void applyResult(GroupMatch match) {
        Map<String, GroupStanding> gs = standings.get(match.getGroup());
        gs.get(match.getTeam1Code()).addResult(match.getScore1(), match.getScore2());
        gs.get(match.getTeam2Code()).addResult(match.getScore2(), match.getScore1());
    }

    // ── Get Sorted Standings for a Group ─────────────────────────────────

    /**
     * Returns the standings for a group, sorted by ranking (best first).
     */
    public List<GroupStanding> getSortedStandings(String group) {
        return standings.get(group).values().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    // ── Group Advancement ────────────────────────────────────────────────

    /**
     * Gets the 1st-place team code for a group.
     */
    public String getGroupWinner(String group) {
        List<GroupStanding> sorted = getSortedStandings(group);
        return sorted.isEmpty() ? null : sorted.get(0).getTeamCode();
    }

    /**
     * Gets the 2nd-place team code for a group.
     */
    public String getGroupRunnerUp(String group) {
        List<GroupStanding> sorted = getSortedStandings(group);
        return sorted.size() < 2 ? null : sorted.get(1).getTeamCode();
    }

    /**
     * Gets the 3rd-place team code for a group.
     */
    public String getGroupThirdPlace(String group) {
        List<GroupStanding> sorted = getSortedStandings(group);
        return sorted.size() < 3 ? null : sorted.get(2).getTeamCode();
    }

    /**
     * Returns all 12 group winners as a map: group → winner team code.
     */
    public Map<String, String> getAllGroupWinners() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String group : GROUP_NAMES) {
            result.put(group, getGroupWinner(group));
        }
        return result;
    }

    /**
     * Returns all 12 group runners-up as a map: group → runner-up team code.
     */
    public Map<String, String> getAllRunnersUp() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String group : GROUP_NAMES) {
            result.put(group, getGroupRunnerUp(group));
        }
        return result;
    }

    /**
     * Returns all 12 third-place teams as a map: group → 3rd-place team code.
     */
    public Map<String, String> getAllThirdPlaces() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String group : GROUP_NAMES) {
            result.put(group, getGroupThirdPlace(group));
        }
        return result;
    }

    /**
     * Determines the 8 best third-place teams across all groups.
     * Sorted by: points (desc), goal diff (desc), goals scored (desc).
     * Returns a sorted list of 8 group letters (the groups whose 3rd-place teams advance).
     */
    public List<String> getBestThirdPlaceGroups() {
        List<GroupStanding> thirdPlaceStandings = new ArrayList<>();
        for (String group : GROUP_NAMES) {
            String thirdCode = getGroupThirdPlace(group);
            if (thirdCode != null) {
                GroupStanding original = standings.get(group).get(thirdCode);
                // Create a copy with group info embedded for sorting
                thirdPlaceStandings.add(original);
            }
        }

        // Sort using the same compareTo (points, GD, GF)
        thirdPlaceStandings.sort(null);

        // Take top 8 and return their group letters
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(8, thirdPlaceStandings.size()); i++) {
            String teamCode = thirdPlaceStandings.get(i).getTeamCode();
            result.add(teams.get(teamCode).getGroup());
        }
        return result;
    }

    /**
     * Returns the sorted list of 8 best third-place team codes (not group letters).
     */
    public List<String> getBestThirdPlaceTeamCodes() {
        return getBestThirdPlaceGroups().stream()
                .map(this::getGroupThirdPlace)
                .collect(Collectors.toList());
    }

    // ── Score Management ─────────────────────────────────────────────────

    /**
     * Sets the score for a group match and recalculates standings.
     */
    public void setGroupMatchScore(String matchId, Integer score1, Integer score2) {
        GroupMatch match = groupMatches.get(matchId);
        if (match == null) {
            throw new IllegalArgumentException("Unknown group match: " + matchId);
        }
        match.setScore1(score1);
        match.setScore2(score2);
        recalculateStandings();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Team getTeam(String code) {
        return teams.get(code);
    }

    public Map<String, Team> getAllTeams() {
        return Collections.unmodifiableMap(teams);
    }

    public Map<String, List<String>> getGroups() {
        return Collections.unmodifiableMap(groups);
    }

    public Map<String, GroupMatch> getGroupMatches() {
        return Collections.unmodifiableMap(groupMatches);
    }

    public Map<String, Map<String, GroupStanding>> getStandings() {
        return Collections.unmodifiableMap(standings);
    }

    public List<GroupMatch> getMatchesForGroup(String group) {
        return groupMatches.values().stream()
                .filter(m -> m.getGroup().equals(group))
                .collect(Collectors.toList());
    }

    /**
     * Resets all group match scores and recalculates standings.
     */
    public void resetAll() {
        for (GroupMatch m : groupMatches.values()) {
            m.setScore1(null);
            m.setScore2(null);
        }
        recalculateStandings();
    }
}