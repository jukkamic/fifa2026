package dev.scaffoldkit.fifa.model;

/**
 * Represents a national team in the tournament.
 */
public class Team {

    private final String code;
    private final String name;
    private final String group;
    private final String flag;

    public Team(String code, String name, String group, String flag) {
        this.code = code;
        this.name = name;
        this.group = group;
        this.flag = flag;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public String getFlag() { return flag; }

    @Override
    public String toString() {
        return code + " (" + name + ")";
    }
}