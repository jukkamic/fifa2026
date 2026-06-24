package dev.scaffoldkit.fifa.service;

import dev.scaffoldkit.fifa.model.KnockoutMatch;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Manages the 32-team knockout bracket (R32 -> R16 -> QF -> SF -> Final).
 *
 * The bracket is split into left and right halves:
 * - Left side:  16 teams -> 8 R32 -> 4 R16 -> 2 QF -> 1 SF
 * - Right side: 16 teams -> 8 R32 -> 4 R16 -> 2 QF -> 1 SF
 * - Center: Final (winner of left SF vs winner of right SF)
 *
 * 32 advancing teams: 12 group winners + 12 runners-up + 8 best third-place teams.
 * The Annex C matrix determines which 3rd-place team faces which group winner.
 */
@Service
public class BracketService {

    private final GroupStageService groupStageService;
    private final ThirdPlaceMatrixService thirdPlaceMatrixService;

    private final Map<Integer, KnockoutMatch> matches = new LinkedHashMap<>();
    private final List<Integer> leftR32 = new ArrayList<>();
    private final List<Integer> leftR16 = new ArrayList<>();
    private final List<Integer> leftQF = new ArrayList<>();
    private final List<Integer> leftSF = new ArrayList<>();
    private final List<Integer> rightR32 = new ArrayList<>();
    private final List<Integer> rightR16 = new ArrayList<>();
    private final List<Integer> rightQF = new ArrayList<>();
    private final List<Integer> rightSF = new ArrayList<>();
    private int finalMatchId;
    private int idCounter = 0;

    public BracketService(GroupStageService groupStageService,
                          ThirdPlaceMatrixService thirdPlaceMatrixService) {
        this.groupStageService = groupStageService;
        this.thirdPlaceMatrixService = thirdPlaceMatrixService;
    }

    @PostConstruct
    public void init() {
        buildBracket();
    }

    // == Bracket Construction ==============================================

    private void buildBracket() {
        matches.clear();
        leftR32.clear(); leftR16.clear(); leftQF.clear(); leftSF.clear();
        rightR32.clear(); rightR16.clear(); rightQF.clear(); rightSF.clear();
        idCounter = 0;

        for (int i = 0; i < 8; i++) leftR32.add(createMatch("R32", "left", i));
        for (int i = 0; i < 4; i++) leftR16.add(createMatch("R16", "left", i));
        for (int i = 0; i < 2; i++) leftQF.add(createMatch("QF", "left", i));
        leftSF.add(createMatch("SF", "left", 0));

        for (int i = 0; i < 8; i++) rightR32.add(createMatch("R32", "right", i));
        for (int i = 0; i < 4; i++) rightR16.add(createMatch("R16", "right", i));
        for (int i = 0; i < 2; i++) rightQF.add(createMatch("QF", "right", i));
        rightSF.add(createMatch("SF", "right", 0));

        finalMatchId = createMatch("Final", "center", 0);

        wireRound(leftR32, leftR16);
        wireRound(leftR16, leftQF);
        wireRound(leftQF, leftSF);
        wireSF(leftSF.get(0), finalMatchId, 1);

        wireRound(rightR32, rightR16);
        wireRound(rightR16, rightQF);
        wireRound(rightQF, rightSF);
        wireSF(rightSF.get(0), finalMatchId, 2);
    }

    private int createMatch(String round, String side, int index) {
        int id = idCounter++;
        matches.put(id, new KnockoutMatch(id, round, side, index));
        return id;
    }

    private void wireRound(List<Integer> from, List<Integer> to) {
        for (int i = 0; i < from.size(); i++) {
            matches.get(from.get(i)).setNextMatchId(to.get(i / 2));
            matches.get(from.get(i)).setNextSlot((i % 2) + 1);
        }
    }

    private void wireSF(Integer sfId, int finalId, int slot) {
        matches.get(sfId).setNextMatchId(finalId);
        matches.get(sfId).setNextSlot(slot);
    }

    // == Seed the Bracket ==================================================

    /**
     * Populates all R32 matches from current group standings.
     *
     * Official 2026 FIFA World Cup Round of 32 bracket (Matches 73-88):
     *
     * LEFT SIDE:
     *   M73: 2nd_A vs 2nd_B            M77: W_I  vs 3rd(C/D/F/G/H)
     *   M74: W_E   vs 3rd(A/B/C/D/F)   M78: 2nd_E vs 2nd_I
     *   M75: W_F   vs 2nd_C            M79: W_A  vs 3rd(C/E/F/H/I)
     *   M76: W_C   vs 2nd_F            M80: W_L  vs 3rd(E/H/I/J/K)
     *
     * RIGHT SIDE:
     *   M81: W_D   vs 3rd(B/E/F/I/J)   M85: W_B  vs 3rd(E/F/G/I/J)
     *   M82: W_G   vs 3rd(A/E/H/I/J)   M86: W_J  vs 2nd_H
     *   M83: 2nd_K vs 2nd_L            M87: W_K  vs 3rd(D/E/I/J/L)
     *   M84: W_H   vs 2nd_J            M88: 2nd_D vs 2nd_G
     */
    public void seedBracket() {
        // Clear everything
        for (KnockoutMatch m : matches.values()) {
            m.setTeam1Code(null);
            m.setTeam2Code(null);
            m.setScore1(null);
            m.setScore2(null);
        }

        Map<String, String> winners = groupStageService.getAllGroupWinners();
        Map<String, String> runnersUp = groupStageService.getAllRunnersUp();
        List<String> bestThirdGroups = groupStageService.getBestThirdPlaceGroups();

        // annex_c.json maps third-place group -> winner group. Invert it here to
        // get winner group -> third-place group, so each group winner's opponent
        // can be resolved by a direct key lookup.
        Map<String, String> winnerToThird = new LinkedHashMap<>();
        if (bestThirdGroups.size() == 8) {
            Map<String, String> thirdToWinner = thirdPlaceMatrixService.solve(bestThirdGroups);
            for (Map.Entry<String, String> entry : thirdToWinner.entrySet()) {
                winnerToThird.put(entry.getValue(), entry.getKey());
            }
        }

        // Resolve 3rd-place team code for a given group
        // (returns null if that group's 3rd place didn't advance)
        java.util.function.Function<String, String> thirdCode = group ->
                groupStageService.getGroupThirdPlace(group);

        // Use list indices instead of hardcoded IDs (lists are populated by buildBracket)
        // -- LEFT SIDE (Matches 73-80) -------------------------------------
        // L0 (M73): 2nd_A vs 2nd_B
        setMatch(leftR32.get(0), runnersUp.get("A"), runnersUp.get("B"));
        // L1 (M74): W_E vs 3rd(A/B/C/D/F)
        setMatch(leftR32.get(1), winners.get("E"), resolveThird("E", winnerToThird, thirdCode));
        // L2 (M75): W_F vs 2nd_C
        setMatch(leftR32.get(2), winners.get("F"), runnersUp.get("C"));
        // L3 (M76): W_C vs 2nd_F
        setMatch(leftR32.get(3), winners.get("C"), runnersUp.get("F"));
        // L4 (M77): W_I vs 3rd(C/D/F/G/H)
        setMatch(leftR32.get(4), winners.get("I"), resolveThird("I", winnerToThird, thirdCode));
        // L5 (M78): 2nd_E vs 2nd_I
        setMatch(leftR32.get(5), runnersUp.get("E"), runnersUp.get("I"));
        // L6 (M79): W_A vs 3rd(C/E/F/H/I)
        setMatch(leftR32.get(6), winners.get("A"), resolveThird("A", winnerToThird, thirdCode));
        // L7 (M80): W_L vs 3rd(E/H/I/J/K)
        setMatch(leftR32.get(7), winners.get("L"), resolveThird("L", winnerToThird, thirdCode));

        // -- RIGHT SIDE (Matches 81-88) ------------------------------------
        // R0 (M81): W_D vs 3rd(B/E/F/I/J)
        setMatch(rightR32.get(0), winners.get("D"), resolveThird("D", winnerToThird, thirdCode));
        // R1 (M82): W_G vs 3rd(A/E/H/I/J)
        setMatch(rightR32.get(1), winners.get("G"), resolveThird("G", winnerToThird, thirdCode));
        // R2 (M83): 2nd_K vs 2nd_L
        setMatch(rightR32.get(2), runnersUp.get("K"), runnersUp.get("L"));
        // R3 (M84): W_H vs 2nd_J
        setMatch(rightR32.get(3), winners.get("H"), runnersUp.get("J"));
        // R4 (M85): W_B vs 3rd(E/F/G/I/J)
        setMatch(rightR32.get(4), winners.get("B"), resolveThird("B", winnerToThird, thirdCode));
        // R5 (M86): W_J vs 2nd_H
        setMatch(rightR32.get(5), winners.get("J"), runnersUp.get("H"));
        // R6 (M87): W_K vs 3rd(D/E/I/J/L)
        setMatch(rightR32.get(6), winners.get("K"), resolveThird("K", winnerToThird, thirdCode));
        // R7 (M88): 2nd_D vs 2nd_G
        setMatch(rightR32.get(7), runnersUp.get("D"), runnersUp.get("G"));
    }

    /**
     * Resolves the 3rd-place team code for a given winner group's opponent.
     * Uses the matrix mapping: if this winner group is assigned a 3rd-place group,
     * return that group's 3rd-place team code.
     */
    private String resolveThird(String winnerGroup,
                                Map<String, String> winnerToThird,
                                java.util.function.Function<String, String> thirdCodeResolver) {
        String thirdGroup = winnerToThird.get(winnerGroup);
        if (thirdGroup == null) return null;
        return thirdCodeResolver.apply(thirdGroup);
    }

    private void setMatch(int matchId, String team1Code, String team2Code) {
        KnockoutMatch m = matches.get(matchId);
        if (m != null) {
            m.setTeam1Code(team1Code);
            m.setTeam2Code(team2Code);
        }
    }

    // == Score Management ==================================================

    /**
     * Sets a knockout match score and propagates the winner forward.
     * Clears any downstream results that depended on the old winner.
     */
    public void setKnockoutScore(int matchId, Integer score1, Integer score2) {
        KnockoutMatch match = matches.get(matchId);
        if (match == null) throw new IllegalArgumentException("Unknown match: " + matchId);

        // Clear old advancement chain
        clearForward(matchId);

        match.setScore1(score1);
        match.setScore2(score2);

        // Advance winner
        advanceWinner(matchId);
    }

    /**
     * Clears this match's result and recursively clears forward.
     */
    private void clearForward(int matchId) {
        KnockoutMatch match = matches.get(matchId);
        if (match == null) return;

        boolean hadResult = match.hasResult();
        match.setScore1(null);
        match.setScore2(null);

        if (hadResult && match.getNextMatchId() != null) {
            KnockoutMatch next = matches.get(match.getNextMatchId());
            if (next != null) {
                // Clear the slot this match fed into
                if (match.getNextSlot() == 1) {
                    next.setTeam1Code(null);
                } else {
                    next.setTeam2Code(null);
                }
                // Recursively clear forward
                clearForward(next.getId());
            }
        }
    }

    /**
     * Advances the winner of the given match to the next match.
     */
    private void advanceWinner(int matchId) {
        KnockoutMatch match = matches.get(matchId);
        if (match == null || !match.hasResult()) return;
        if (match.getNextMatchId() == null) return;

        String winner = match.getWinnerCode();
        if (winner == null) return; // Draw = no advancement

        KnockoutMatch next = matches.get(match.getNextMatchId());
        if (next == null) return;

        if (match.getNextSlot() == 1) {
            next.setTeam1Code(winner);
        } else {
            next.setTeam2Code(winner);
        }
    }

    // == Getters ===========================================================

    public Map<Integer, KnockoutMatch> getMatches() {
        return Collections.unmodifiableMap(matches);
    }

    public KnockoutMatch getMatch(int id) {
        return matches.get(id);
    }

    public List<KnockoutMatch> getMatchesByRoundAndSide(String round, String side) {
        return matches.values().stream()
                .filter(m -> m.getRound().equals(round) && m.getSide().equals(side))
                .sorted((a, b) -> Integer.compare(a.getMatchIndex(), b.getMatchIndex()))
                .toList();
    }

    public KnockoutMatch getFinalMatch() {
        return matches.get(finalMatchId);
    }

    /**
     * Resets all knockout scores and re-seeds from group results.
     */
    public void resetAndReseed() {
        buildBracket();
        seedBracket();
    }

    /**
     * Resets all knockout scores only (keeps current team assignments).
     */
    public void resetScores() {
        for (KnockoutMatch m : matches.values()) {
            m.setScore1(null);
            m.setScore2(null);
            // Clear non-R32 team assignments (they come from advancement)
            if (!"R32".equals(m.getRound())) {
                m.setTeam1Code(null);
                m.setTeam2Code(null);
            }
        }
    }
}