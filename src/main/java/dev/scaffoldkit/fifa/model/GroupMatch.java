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
    private String matchDate;
    private Double odds1;
    private Double oddsDraw;
    private Double odds2;
    private String marketURL;

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
    public String getMarketURL() { return this.marketURL; }
    public void setMarketURL(String marketURL) { this.marketURL = marketURL; }

    public void setScore1(Integer score1) { this.score1 = score1; }
    public void setScore2(Integer score2) { this.score2 = score2; }

    public String getMatchDate() { return matchDate; }
    public void setMatchDate(String matchDate) { this.matchDate = matchDate; }
    public Double getOdds1() { return odds1; }
    public void setOdds1(Double odds1) { this.odds1 = odds1; }
    public Double getOddsDraw() { return oddsDraw; }
    public void setOddsDraw(Double oddsDraw) { this.oddsDraw = oddsDraw; }
    public Double getOdds2() { return odds2; }
    public void setOdds2(Double odds2) { this.odds2 = odds2; }

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