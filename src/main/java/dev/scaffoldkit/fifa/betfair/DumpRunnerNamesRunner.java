package dev.scaffoldkit.fifa.betfair;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Standalone diagnostic script that collects all Betfair runner (team) names
 * from World Cup / FIFA Match Odds markets and writes them to a plain text file.
 *
 * <p>Activate by running:
 * <pre>
 *   gradlew bootRun --args="--betfair.dump-runner-names=true"
 * </pre>
 *
 * <p>This class is temporary and should be removed once the code-to-name
 * mapping is established.
 */
@Component
@Profile("!prod")
class DumpRunnerNamesRunner implements CommandLineRunner {

    private final Environment env;
    private final ApplicationContext ctx;
    private final BetfairIntegrationService service;

    DumpRunnerNamesRunner(Environment env, ApplicationContext ctx,
                          BetfairIntegrationService service) {
        this.env = env;
        this.ctx = ctx;
        this.service = service;
    }

    @Override
    public void run(String... args) {
        boolean enabled = env.getProperty("betfair.dump-runner-names", Boolean.class, false);
        if (!enabled) {
            return;
        }

        System.out.println("=== Betfair Runner Names Dump ===");
        System.out.println("Collecting team names from World Cup / FIFA markets...");

        Set<String> teamNames = service.collectWorldCupRunnerNames();

        if (teamNames.isEmpty()) {
            System.out.println("No team names found.");
            SpringApplication.exit(ctx, () -> 0);
            return;
        }

        // Write to file
        Path outFile = Path.of("betfair-runner-names.txt");
        try {
            var lines = new java.util.ArrayList<String>();
            lines.add("# Betfair runner names extracted " + LocalDateTime.now());
            lines.add("# Query: \"World Cup\" and \"FIFA\" - MATCH_ODDS markets only");
            lines.add("# Total unique team names: " + teamNames.size());
            lines.add("");
            teamNames.stream().sorted().forEach(lines::add);
            Files.write(outFile, lines);
        } catch (IOException e) {
            System.out.println("ERROR writing file: " + e.getMessage());
            SpringApplication.exit(ctx, () -> 1);
            return;
        }

        System.out.println("Wrote " + teamNames.size() + " team names to " + outFile.toAbsolutePath());

        // Also print to console (plain System.out, no logging framework)
        System.out.println();
        System.out.println("--- Team names (alphabetical) ---");
        teamNames.stream().sorted().forEach(System.out::println);
        System.out.println("--- End (" + teamNames.size() + " total) ---");

        System.out.println("Exiting application after runner name dump...");
        SpringApplication.exit(ctx, () -> 0);
    }
}