# Setting up

See BETFAIR.md


## Railway.app

Run these commands one at a time and save the output by pasting from clipboard to
BETFAIR_CERT_B64 and BETFAIR_KEY_B64

```bash
 base64 -w 0 client-2048.crt | clip
 base64 -w 0 client-2048.key | clip
```

Link to Railway: Go to Railway.app, create an account, and click New Project -> Deploy from GitHub repo.

Select your repo: Choose your fifa2026 repository. Railway will automatically detect the Dockerfile and start building it.

The Storage Trick (Crucial): By default, Docker containers are ephemeral. If your app goes to sleep or you push a new code update, the local file system resets, which would wipe out your friends' H2 database predictions.

In your Railway dashboard, click your newly deployed service.

Go to Volumes and click Create Volume. Mount it to /app/data.

Set the Environment Variables: Go to the Variables tab in Railway. You need to tell Spring Boot to save the database inside that new persistent volume, and you need to pass in your Betfair credentials if you are using them.

SPRING_DATASOURCE_URL = jdbc:h2:file:/app/data/predictions
BETFAIR_API_KEY = your_key (if applicable)
BETFAIR_USERNAME = your_username (if applicable)
BETFAIR_PASSWORD = your_password (if applicable)
BETFAIR_CERT_B64
BETFAIR_KEY_B64


Generate the Domain: Go to the Settings tab in Railway, find the "Networking" section, and click Generate Domain. This will give you a public URL (e.g., fifa-production.up.railway.app).

## Dumping Betfair Team Names

Betfair uses its own spelling and formatting for country names (e.g. `Austria (W)`, `Germany (W)`). Since these can change or differ from what we expect, there's a built-in tool to fetch the raw team names straight from Betfair and save them to a file.

**What it does:** Connects to Betfair, searches all World Cup and FIFA match-odds markets, collects every team name it finds, and writes a clean deduplicated list to `betfair-runner-names.txt`.

**How to run:**

```
.\gradlew.bat bootRun --args="--betfair.dump-runner-names=true"
```

The app will start, write the file, print the results to the terminal, and then exit. You can re-run this any time later if you suspect a country name spelling has changed or if new markets have appeared.
