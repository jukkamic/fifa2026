package dev.scaffoldkit.fifa.service;

import dev.scaffoldkit.fifa.model.GroupMatch;
import dev.scaffoldkit.fifa.model.KnockoutMatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that each Round-of-32 (first knockout round) game is paired with
 * the correct two teams according to the official bracket structure and
 * the Annex C third-place matrix in src/main/resources/annex_c.json.
 *
 * <p>The test sets up deterministic group-stage results, seeds the bracket,
 * and then asserts every R32 match has the expected pair of teams. The
 * third-place opponents are derived by correctly interpreting annex_c.json
 * (which maps third-place group -> winner group), then inverting to find
 * which third-place group each winner faces.
 */
@SpringBootTest
class BracketPairingTest {

    @Autowired
    private GroupStageService groupStageService;

    @Autowired
    private BracketService bracketService;

    @Autowired
    private ThirdPlaceMatrixService thirdPlaceMatrixService;

    @Test
    void eachR32GameIsPairedWithCorrectTwoTeamsPerAnnexC() {
        // --- Set up deterministic group-stage results ---
        setupGroupResults();

        // Verify the 8 best third-place groups are E through L
        List<String> bestThird = groupStageService.getBestThirdPlaceGroups();
        assertEquals(8, bestThird.size(), "Exactly 8 third-place teams should advance");

        List<String> sortedBestThird = new ArrayList<>(bestThird);
        Collections.sort(sortedBestThird);
        assertEquals(List.of("E", "F", "G", "H", "I", "J", "K", "L"), sortedBestThird,
                "Best third-place groups should be E-L in this scenario");

        // --- Build team lookups ---
        Map<String, String> winners = groupStageService.getAllGroupWinners();
        Map<String, String> runnersUp = groupStageService.getAllRunnersUp();
        Map<String, String> thirdPlaces = groupStageService.getAllThirdPlaces();

        // --- Resolve Annex C third-place opponents ---
        // annex_c.json maps: third-place group -> winner group.
        // Invert to get: winner group -> third-place group, so we can look
        // up which third-place team each group winner should face.
        Map<String, String> annexC = thirdPlaceMatrixService.solve(bestThird);
        Map<String, String> winnerToThird = new HashMap<>();
        for (Map.Entry<String, String> entry : annexC.entrySet()) {
            // entry = { thirdGroup: winnerGroup }
            winnerToThird.put(entry.getValue(), entry.getKey());
        }

        // --- Seed the bracket from group results ---
        bracketService.seedBracket();

        // ===== LEFT SIDE (M73-M80) =====

        // M73: 2nd_A vs 2nd_B
        assertR32("left", 0, runnersUp.get("A"), runnersUp.get("B"), "M73: 2nd_A vs 2nd_B");

        // M74: W_E vs 3rd (winner E's third-place opponent per Annex C)
        assertR32("left", 1, winners.get("E"),
                thirdPlaces.get(winnerToThird.get("E")), "M74: W_E vs 3rd");

        // M75: W_F vs 2nd_C
        assertR32("left", 2, winners.get("F"), runnersUp.get("C"), "M75: W_F vs 2nd_C");

        // M76: W_C vs 2nd_F
        assertR32("left", 3, winners.get("C"), runnersUp.get("F"), "M76: W_C vs 2nd_F");

        // M77: W_I vs 3rd
        assertR32("left", 4, winners.get("I"),
                thirdPlaces.get(winnerToThird.get("I")), "M77: W_I vs 3rd");

        // M78: 2nd_E vs 2nd_I
        assertR32("left", 5, runnersUp.get("E"), runnersUp.get("I"), "M78: 2nd_E vs 2nd_I");

        // M79: W_A vs 3rd
        assertR32("left", 6, winners.get("A"),
                thirdPlaces.get(winnerToThird.get("A")), "M79: W_A vs 3rd");

        // M80: W_L vs 3rd
        assertR32("left", 7, winners.get("L"),
                thirdPlaces.get(winnerToThird.get("L")), "M80: W_L vs 3rd");

        // ===== RIGHT SIDE (M81-M88) =====

        // M81: W_D vs 3rd
        assertR32("right", 0, winners.get("D"),
                thirdPlaces.get(winnerToThird.get("D")), "M81: W_D vs 3rd");

        // M82: W_G vs 3rd
        assertR32("right", 1, winners.get("G"),
                thirdPlaces.get(winnerToThird.get("G")), "M82: W_G vs 3rd");

        // M83: 2nd_K vs 2nd_L
        assertR32("right", 2, runnersUp.get("K"), runnersUp.get("L"), "M83: 2nd_K vs 2nd_L");

        // M84: W_H vs 2nd_J
        assertR32("right", 3, winners.get("H"), runnersUp.get("J"), "M84: W_H vs 2nd_J");

        // M85: W_B vs 3rd
        assertR32("right", 4, winners.get("B"),
                thirdPlaces.get(winnerToThird.get("B")), "M85: W_B vs 3rd");

        // M86: W_J vs 2nd_H
        assertR32("right", 5, winners.get("J"), runnersUp.get("H"), "M86: W_J vs 2nd_H");

        // M87: W_K vs 3rd
        assertR32("right", 6, winners.get("K"),
                thirdPlaces.get(winnerToThird.get("K")), "M87: W_K vs 3rd");

        // M88: 2nd_D vs 2nd_G
        assertR32("right", 7, runnersUp.get("D"), runnersUp.get("G"), "M88: 2nd_D vs 2nd_G");
    }

    // == Assertion Helper ==================================================

    /**
     * Asserts that the R32 match at the given side/index has exactly the
     * expected team1 and team2 codes.
     */
    private void assertR32(String side, int index, String expectedTeam1,
                           String expectedTeam2, String label) {
        List<KnockoutMatch> r32 = bracketService.getMatchesByRoundAndSide("R32", side);
        KnockoutMatch match = r32.get(index);
        assertEquals(expectedTeam1, match.getTeam1Code(),
                label + " -- team1 mismatch");
        assertEquals(expectedTeam2, match.getTeam2Code(),
                label + " -- team2 mismatch");
    }

    // == Group Result Setup ================================================

    /**
     * Sets deterministic group results for all 12 groups so that in every
     * group the teams finish in registration order:
     *   team[0] = winner, team[1] = runner-up, team[2] = 3rd, team[3] = 4th.
     *
     * Groups A-D: 3rd-place team has very negative goal difference (does NOT
     *             advance among the best eight third-place teams).
     * Groups E-L: 3rd-place team has positive goal difference (advances).
     */
    private void setupGroupResults() {
        for (String g : List.of("A", "B", "C", "D")) {
            setupGroup(g, false);
        }
        for (String g : List.of("E", "F", "G", "H", "I", "J", "K", "L")) {
            setupGroup(g, true);
        }
    }

    /**
     * Sets all 6 round-robin results for one group. The lower-indexed team
     * always wins. Goal margins are adjusted so the 3rd-place team's (index 2)
     * goal difference is either high or low depending on {@code thirdAdvances}.
     *
     * @param group          the group letter
     * @param thirdAdvances  if true, the 3rd-place team gets a positive GD;
     *                       if false, a very negative GD
     */
    private void setupGroup(String group, boolean thirdAdvances) {
        List<String> teams = groupStageService.getGroups().get(group);

        for (GroupMatch match : groupStageService.getMatchesForGroup(group)) {
            int i1 = teams.indexOf(match.getTeam1Code());
            int i2 = teams.indexOf(match.getTeam2Code());
            int winnerIdx = Math.min(i1, i2);
            int loserIdx = Math.max(i1, i2);

            int s1; // score for team1 (home)
            int s2; // score for team2 (away)

            if (winnerIdx == 2 && loserIdx == 3) {
                // 3rd-place team beats 4th-place team
                int margin = thirdAdvances ? 10 : 1;
                s1 = (i1 == 2) ? margin : 0;
                s2 = (i2 == 2) ? margin : 0;
            } else if (loserIdx == 2) {
                // 3rd-place team loses to 1st or 2nd
                int margin = thirdAdvances ? 1 : 10;
                s1 = (i1 == winnerIdx) ? margin : 0;
                s2 = (i2 == winnerIdx) ? margin : 0;
            } else {
                // Match not involving the 3rd-place team
                s1 = (i1 == winnerIdx) ? 2 : 0;
                s2 = (i2 == winnerIdx) ? 2 : 0;
            }

            groupStageService.setGroupMatchScore(match.getId(), s1, s2);
        }
    }
}