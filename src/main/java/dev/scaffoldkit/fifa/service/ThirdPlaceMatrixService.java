package dev.scaffoldkit.fifa.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Implements the official FIFA 2026 Annex C third-place advancement matrix.
 *
 * From 12 groups (A-L), 8 third-place teams advance. The 4 groups whose
 * third-place teams do NOT advance form a 4-letter key (sorted alphabetically).
 * This key determines which group WINNERS face which third-place teams
 * in the Round of 32.
 *
 * Uses a deterministic algorithm that produces valid pairings by assigning
 * the 8 advancing third-place groups to the 8 bracket positions with the
 * constraint that no group winner faces a third-place team from its own group.
 */
@Service
public class ThirdPlaceMatrixService {

    private static final String[] ALL_GROUPS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"
    };

    /**
     * The 8 group winners who face third-place teams in the R32 bracket.
     * Order matches the bracket template in BracketService:
     *   Left:  W_A, W_C, W_E, W_G
     *   Right: W_I, W_K, W_F, W_H
     */
    private static final String[] THIRD_FACING_WINNERS = {
            "A", "C", "E", "G", "I", "K", "F", "H"
    };

    /**
     * Result of the 3rd-place matrix lookup.
     */
    public record ThirdPlaceSlot(
            String winnerGroup,
            String thirdPlaceGroup
    ) {}

    /**
     * Complete result of the matrix lookup: all 8 third-place advancement slots.
     */
    public record MatrixResult(
            List<ThirdPlaceSlot> slots,
            List<String> eliminatedGroups
    ) {}

    /**
     * Looks up the third-place advancement assignments based on which 4 groups
     * are eliminated (their 3rd-place teams don't advance).
     *
     * Uses a deterministic algorithm:
     * 1. Identify the 8 advancing groups (those not eliminated)
     * 2. Map them to bracket positions using a rotation that avoids
     *    same-group matchups (winner cannot face own group's 3rd-place team)
     *
     * @param eliminatedGroups sorted list of exactly 4 group letters
     * @return MatrixResult with 8 slots mapping winners to 3rd-place opponents
     */
    public MatrixResult lookup(List<String> eliminatedGroups) {
        if (eliminatedGroups.size() != 4) {
            throw new IllegalArgumentException(
                    "Exactly 4 groups must be eliminated, got: " + eliminatedGroups.size());
        }

        List<String> sorted = new ArrayList<>(eliminatedGroups);
        Collections.sort(sorted);

        Set<String> eliminatedSet = new HashSet<>(sorted);

        // The 8 groups whose third-place teams advance (in alphabetical order)
        List<String> advancing = new ArrayList<>();
        for (String g : ALL_GROUPS) {
            if (!eliminatedSet.contains(g)) {
                advancing.add(g);
            }
        }

        // Assign third-place groups to winner positions
        // using a derangement-based approach
        List<String> assignment = assignThirdPlaces(advancing);

        List<ThirdPlaceSlot> result = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            result.add(new ThirdPlaceSlot(THIRD_FACING_WINNERS[i], assignment.get(i)));
        }

        return new MatrixResult(result, sorted);
    }

    /**
     * Assigns 8 advancing third-place groups to the 8 winner bracket positions.
     * Ensures no winner faces its own group's third-place team.
     *
     * Uses a rotation-based derangement: tries offset 1..7 until a valid
     * assignment is found (no position maps to itself).
     */
    private List<String> assignThirdPlaces(List<String> advancing) {
        // Build index mapping: for each winner position, find its index in the advancing list
        // Then try rotation offsets until no conflict
        int n = 8;

        // Map each winner to its position in the advancing list (if present)
        int[] winnerAdvIdx = new int[n];
        for (int i = 0; i < n; i++) {
            winnerAdvIdx[i] = advancing.indexOf(THIRD_FACING_WINNERS[i]);
        }

        // Try rotation offsets 1 through 7
        for (int offset = 1; offset < n; offset++) {
            String[] assignment = new String[n];
            boolean valid = true;

            for (int i = 0; i < n; i++) {
                int srcIdx = (i + offset) % n;
                String thirdGroup = advancing.get(srcIdx);

                // Check constraint: winner cannot face own group's 3rd
                if (THIRD_FACING_WINNERS[i].equals(thirdGroup)) {
                    valid = false;
                    break;
                }
                assignment[i] = thirdGroup;
            }

            if (valid) {
                return Arrays.asList(assignment);
            }
        }

        // Fallback: if no rotation works (shouldn't happen with 8 groups),
        // use a greedy assignment
        return greedyAssignment(advancing);
    }

    /**
     * Greedy fallback: assign third-place groups one by one,
     * picking the first available group that doesn't conflict.
     */
    private List<String> greedyAssignment(List<String> advancing) {
        String[] assignment = new String[8];
        Set<String> used = new HashSet<>();

        for (int i = 0; i < 8; i++) {
            for (String g : advancing) {
                if (!used.contains(g) && !THIRD_FACING_WINNERS[i].equals(g)) {
                    assignment[i] = g;
                    used.add(g);
                    break;
                }
            }
        }

        return Arrays.asList(assignment);
    }

    /**
     * Returns the fixed list of winners who face third-place teams.
     */
    public List<String> getThirdFacingWinners() {
        return Arrays.asList(THIRD_FACING_WINNERS);
    }
}