package dev.scaffoldkit.fifa.model;

import java.util.Objects;

/**
 * A group-stage match between two teams.
 * Stores scores and updates group standings when a result is set.
 */
public class GroupMatch {

    private final String id;
    private final String group;
    private final String team1Code;
    private final String team2Code;
    private Integer score1;
    private Integer score2;

    public GroupMatch(String id, String group, String team1Code, String team2Code) {
        this.id = id;
        this.group = group;
        this.team1Code = team1Code;
        this.team2Code = team2Code;
    }

    public String getId() { return id; }
    public String getGroup() { return group; }
    public String getTeam1Code() { return team1Code; }
    public String getTeam2Code() { return team2Code; }
    public Integer getScore1() { return score1; }
    public Integer getScore2() { return score2; }

    public void setScore1(Integer score1) { this.score1 = score1; }
    public void setScore2(Integer score2) { this.score2 = score2; }

    public boolean hasResult() {
        return score1 != null && score2 != null;
    }

    /**
     * Returns the winning team code, or null if draw or not yet played.
     */
    public String getWinnerCode() {
        if (!hasResult()) return null;
        if (score1 > score2) return team1Code;
        if (score2 > score1) return team2Code;
        return null; // Draw
    }

    /**
     * Returns the losing team code, or null if draw or not yet played.
     */
    public String getLoserCode() {
        if (!hasResult()) return null;
        if (score1 > score2) return team2Code;
        if (score2 > score1) return team1Code;
        return null; // Draw
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupMatch that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("%s: %s %s - %s %s", id, team1Code,
                score1 != null ? score1 : "?",
                score2 != null ? score2 : "?",
                team2Code);
    }
}