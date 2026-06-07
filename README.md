# Setting up

See BETFAIR.md

## Dumping Betfair Team Names

Betfair uses its own spelling and formatting for country names (e.g. `Austria (W)`, `Germany (W)`). Since these can change or differ from what we expect, there's a built-in tool to fetch the raw team names straight from Betfair and save them to a file.

**What it does:** Connects to Betfair, searches all World Cup and FIFA match-odds markets, collects every team name it finds, and writes a clean deduplicated list to `betfair-runner-names.txt`.

**How to run:**

```
.\gradlew.bat bootRun --args="--betfair.dump-runner-names=true"
```

The app will start, write the file, print the results to the terminal, and then exit. You can re-run this any time later if you suspect a country name spelling has changed or if new markets have appeared.
