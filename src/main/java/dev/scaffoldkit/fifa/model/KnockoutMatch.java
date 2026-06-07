package dev.scaffoldkit.fifa.model;

import java.util.Objects;

/**
 * A knockout-stage match. Teams are set from group advancement or previous round winners.
 */
public class KnockoutMatch {

    private final int id;
    private String team1Code;
    private String team2Code;
    private Integer score1;
    private Integer score2;
    private final String round;   // R32, R16, QF, SF, Final
    private final String side;    // left, right, center
    private final int matchIndex;
    private Integer nextMatchId;  // ID of the next match the winner advances to
    private Integer nextSlot;     // 1 or 2 — which slot in the next match

    public KnockoutMatch(int id, String round, String side, int matchIndex) {
        this.id = id;
        this.round = round;
        this.side = side;
        this.matchIndex = matchIndex;
    }

    public int getId() { return id; }
    public String getTeam1Code() { return team1Code; }
    public String getTeam2Code() { return team2Code; }
    public Integer getScore1() { return score1; }
    public Integer getScore2() { return score2; }
    public String getRound() { return round; }
    public String getSide() { return side; }
    public int getMatchIndex() { return matchIndex; }
    public Integer getNextMatchId() { return nextMatchId; }
    public Integer getNextSlot() { return nextSlot; }

    public void setTeam1Code(String team1Code) { this.team1Code = team1Code; }
    public void setTeam2Code(String team2Code) { this.team2Code = team2Code; }
    public void setScore1(Integer score1) { this.score1 = score1; }
    public void setScore2(Integer score2) { this.score2 = score2; }
    public void setNextMatchId(Integer nextMatchId) { this.nextMatchId = nextMatchId; }
    public void setNextSlot(Integer nextSlot) { this.nextSlot = nextSlot; }

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
        return null; // Draw — no winner in knockout
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KnockoutMatch that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("M%d [%s-%s %s] %s %s - %s %s", id, round, side, matchIndex,
                team1Code != null ? team1Code : "TBD",
                score1 != null ? score1 : "?",
                score2 != null ? score2 : "?",
                team2Code != null ? team2Code : "TBD");
    }
}