package dev.scaffoldkit.fifa.model;

import java.util.Objects;

/**
 * Tracks a team's standing within a group: points, goals for, goals against,
 * and derived goal differential.
 */
public class GroupStanding implements Comparable<GroupStanding> {

    private final String teamCode;
    private int points;
    private int goalsFor;
    private int goalsAgainst;
    private int played;

    public GroupStanding(String teamCode) {
        this.teamCode = teamCode;
        this.points = 0;
        this.goalsFor = 0;
        this.goalsAgainst = 0;
        this.played = 0;
    }

    public String getTeamCode() { return teamCode; }
    public int getPoints() { return points; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public int getGoalDifference() { return goalsFor - goalsAgainst; }
    public int getPlayed() { return played; }

    public void addResult(int goalsScored, int goalsConceded) {
        this.goalsFor += goalsScored;
        this.goalsAgainst += goalsConceded;
        this.played++;
        if (goalsScored > goalsConceded) {
            this.points += 3; // Win
        } else if (goalsScored == goalsConceded) {
            this.points += 1; // Draw
        }
        // Loss = 0 points
    }

    /**
     * Comparison for group standings:
     * 1) Points (desc)
     * 2) Goal difference (desc)
     * 3) Goals scored (desc)
     */
    @Override
    public int compareTo(GroupStanding other) {
        int cmp = Integer.compare(other.points, this.points);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(other.getGoalDifference(), this.getGoalDifference());
        if (cmp != 0) return cmp;
        return Integer.compare(other.goalsFor, this.goalsFor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupStanding that)) return false;
        return teamCode.equals(that.teamCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamCode);
    }

    @Override
    public String toString() {
        return String.format("%s: P%d W? D? L? GF%d GA%d GD%+d PTS%d",
                teamCode, played, goalsFor, goalsAgainst, getGoalDifference(), points);
    }
}