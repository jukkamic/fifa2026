package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketBook;
import dev.scaffoldkit.fifa.betfair.model.BetfairMarketCatalog;
import dev.scaffoldkit.fifa.betfair.model.BetfairOddsSnapshot;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Standalone runnable application that snapshots Betfair odds and optionally
 * uploads them to the production web service, without requiring a Spring Boot
 * web server.
 *
 * <p>
 * Replicates the logic of {@link BetfairOddsSnapshotScheduler} and
 * {@link BetfairIntegrationService#snapshotOddsLocally()} but runs as a simple
 * {@code java -jar} command.
 *
 * <h2>Usage</h2>
 * 
 * <pre>
 *   java -jar fifa-standalone.jar [options]
 * </pre>
 *
 * <h3>Options</h3>
 * <ul>
 * <li>{@code --once} - Run a single snapshot and exit (default)</li>
 * <li>{@code --loop} - Run continuously, taking snapshots at fixed intervals</li>
 * <li>{@code --interval <minutes>} - Interval between snapshots in loop mode
 * (default: 13)</li>
 * <li>{@code --initial-delay <minutes>} - Delay before first snapshot in loop
 * mode (default: 0)</li>
 * <li>{@code --output <path>} - Output file path (default:
 * ./fallback-odds.json)</li>
 * <li>{@code --no-push} - Skip pushing to the production server</li>
 * <li>{@code --help} - Show usage information</li>
 * </ul>
 *
 * <h3>Configuration (Environment Variables)</h3>
 * <ul>
 * <li>{@code BETFAIR_CERT_PATH} - Path to directory containing the {@code ssl/}
 * certificate folder</li>
 * <li>{@code BETFAIR_API_KEY} - Betfair API key</li>
 * <li>{@code BETFAIR_USERNAME} - Betfair username</li>
 * <li>{@code BETFAIR_PASSWORD} - Betfair password</li>
 * <li>{@code ADMIN_CLOUDFLARE_JWT} - Cloudflare Access JWT for production
 * upload (optional; if not set, push is skipped)</li>
 * </ul>
 *
 * <h3>Example</h3>
 * 
 * <pre>
 *   java -jar fifa-standalone.jar --loop --interval 13
 * </pre>
 */
public class BetfairOddsSnapshotApp {

    private static final Logger log = LoggerFactory.getLogger(BetfairOddsSnapshotApp.class);

    // ── Constants ──────────────────────────────────────────────────────────

    private static final String PROD_ODDS_UPLOAD_URL =
            "https://fifa2026.scaffoldkit.dev/api/admin/odds/upload";

    private static final String CERT_FILE = "ssl/client-2048.crt";
    private static final String KEY_FILE = "ssl/client-2048.key";
    private static final String KEYSTORE_PASSWORD = "betfair";

    private static final int DEFAULT_INTERVAL_MINUTES = 13;
    private static final int BATCH_SIZE = 40;

    // ── Entry Point ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Configure logback root level programmatically (without Spring Boot,
        // logback defaults to DEBUG which is too verbose)
        try {
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        } catch (Exception e) {
            // If logback is not on the classpath, fall back to defaults
            System.err.println("Note: could not configure logback level: " + e.getMessage());
        }

        AppConfig config = parseArgs(args);

        if (config.help) {
            printUsage();
            return;
        }

        log.info("=== Betfair Odds Snapshot Standalone App ===");
        log.info("Mode: {}", config.loop ? "LOOP (interval=" + config.intervalMinutes + " min)" : "ONCE");
        log.info("Output: {}", config.outputPath);

        BetfairProperties props;
        try {
            props = loadConfigFromEnv();
        } catch (IllegalStateException e) {
            log.error("Configuration error: {}", e.getMessage());
            log.error("");
            log.error("Required environment variables:");
            log.error("  BETFAIR_CERT_PATH  - Path to directory containing ssl/ folder");
            log.error("  BETFAIR_API_KEY    - Betfair API key");
            log.error("  BETFAIR_USERNAME   - Betfair username");
            log.error("  BETFAIR_PASSWORD   - Betfair password");
            log.error("");
            log.error("Optional:");
            log.error("  ADMIN_CLOUDFLARE_JWT - Cloudflare JWT for production upload");
            System.exit(1);
            return;
        }

        String cloudflareJwt = System.getenv("ADMIN_CLOUDFLARE_JWT");
        if (cloudflareJwt == null) {
            cloudflareJwt = "";
        }

        RestTemplate authRestTemplate;
        RestTemplate apiRestTemplate;
        try {
            authRestTemplate = buildMtlsRestTemplate(props, Duration.ofSeconds(10),
                    Duration.ofSeconds(15));
            apiRestTemplate = buildMtlsRestTemplate(props, Duration.ofSeconds(10),
                    Duration.ofSeconds(30));
        } catch (Exception e) {
            log.error("Failed to create mTLS HTTP clients: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        BetfairAuthClient authClient = new BetfairAuthClient(props, authRestTemplate);
        BetfairMarketClient marketClient = new BetfairMarketClient(props, apiRestTemplate);

        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .findAndRegisterModules();

        if (config.loop) {
            runLoop(authClient, marketClient, objectMapper, config, cloudflareJwt, props);
        } else {
            runOnce(authClient, marketClient, objectMapper, config, cloudflareJwt);
        }
    }

    // ── Run Modes ──────────────────────────────────────────────────────────

    /**
     * Runs a single snapshot and exits.
     */
    private static void runOnce(BetfairAuthClient authClient,
            BetfairMarketClient marketClient,
            ObjectMapper objectMapper,
            AppConfig config,
            String cloudflareJwt) {
        try {
            doSnapshot(authClient, marketClient, objectMapper, config, cloudflareJwt);
            log.info("Snapshot completed successfully.");
        } catch (Exception e) {
            log.error("Snapshot failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Runs snapshots on a fixed-delay schedule. The first snapshot runs after
     * the configured initial delay, then repeats at the given interval. Runs
     * until the process is killed (Ctrl+C).
     */
    private static void runLoop(BetfairAuthClient authClient,
            BetfairMarketClient marketClient,
            ObjectMapper objectMapper,
            AppConfig config,
            String cloudflareJwt,
            BetfairProperties props) {

        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "betfair-snapshot-scheduler");
                    t.setDaemon(false);
                    return t;
                });

        log.info("Starting loop mode: initialDelay={} min, interval={} min",
                config.initialDelayMinutes, config.intervalMinutes);

        // Track the session token across runs so we only authenticate once
        final String[] cachedSession = { null };

        Runnable task = () -> {
            log.info("--- Scheduled Betfair odds snapshot starting... ---");
            try {
                doSnapshotWithCachedSession(authClient, marketClient, objectMapper,
                        config, cloudflareJwt, cachedSession);
                log.info("--- Scheduled snapshot completed successfully. ---");
            } catch (Exception e) {
                log.error("--- Scheduled snapshot failed: {} ---", e.getMessage(), e);
                // Invalidate session so next run re-authenticates
                cachedSession[0] = null;
            }
        };

        scheduler.scheduleWithFixedDelay(task,
                config.initialDelayMinutes,
                config.intervalMinutes,
                TimeUnit.MINUTES);

        // Register shutdown hook for clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down scheduler...");
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Scheduler did not terminate cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("Goodbye.");
        }));

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Snapshot Logic ─────────────────────────────────────────────────────

    /**
     * Executes a full snapshot: authenticate, fetch catalogue + books,
     * serialize, save to file, push to production.
     */
    private static void doSnapshot(BetfairAuthClient authClient,
            BetfairMarketClient marketClient,
            ObjectMapper objectMapper,
            AppConfig config,
            String cloudflareJwt) throws Exception {
        log.info("Step 1: Authenticating with Betfair (mTLS certlogin)...");
        String sessionToken = authClient.login();
        if (sessionToken == null) {
            throw new IllegalStateException(
                    "Betfair authentication failed - check credentials and certificates");
        }
        log.info("Authentication successful (session token length: {})", sessionToken.length());

        doFetchAndSave(marketClient, objectMapper, sessionToken, config, cloudflareJwt);
    }

    /**
     * Variant of {@link #doSnapshot} that caches the session token between
     * scheduled runs. If the cached token is null, re-authenticates.
     */
    private static void doSnapshotWithCachedSession(BetfairAuthClient authClient,
            BetfairMarketClient marketClient,
            ObjectMapper objectMapper,
            AppConfig config,
            String cloudflareJwt,
            String[] cachedSession) throws Exception {

        String sessionToken = cachedSession[0];
        if (sessionToken == null) {
            log.info("Step 1: Authenticating with Betfair (mTLS certlogin)...");
            sessionToken = authClient.login();
            if (sessionToken == null) {
                throw new IllegalStateException(
                        "Betfair authentication failed - check credentials and certificates");
            }
            cachedSession[0] = sessionToken;
            log.info("Authentication successful (session token length: {})",
                    sessionToken.length());
        } else {
            log.debug("Reusing cached session token");
        }

        doFetchAndSave(marketClient, objectMapper, sessionToken, config, cloudflareJwt);
    }

    /**
     * Fetches market catalogue + books, builds a snapshot, saves it locally
     * and optionally pushes to production.
     */
    private static void doFetchAndSave(BetfairMarketClient marketClient,
            ObjectMapper objectMapper,
            String sessionToken,
            AppConfig config,
            String cloudflareJwt) throws Exception {

        // Step 2: Fetch market catalogue
        log.info("Step 2: Fetching market catalogue...");
        List<BetfairMarketCatalog> catalogue =
                marketClient.listMarketCatalogue(sessionToken);
        if (catalogue.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to fetch market catalogue - no markets returned");
        }
        log.info("Received {} market(s) from catalogue", catalogue.size());

        // Step 3: Fetch market books (batched, Betfair limit: 40 per call)
        List<String> marketIds = new ArrayList<>();
        for (BetfairMarketCatalog market : catalogue) {
            marketIds.add(market.marketId());
        }

        log.info("Step 3: Fetching market books for {} market(s)...", marketIds.size());
        List<BetfairMarketBook> books = new ArrayList<>();
        for (int i = 0; i < marketIds.size(); i += BATCH_SIZE) {
            List<String> batch = marketIds.subList(i,
                    Math.min(i + BATCH_SIZE, marketIds.size()));
            List<BetfairMarketBook> batchBooks =
                    marketClient.listMarketBook(sessionToken, batch);
            books.addAll(batchBooks);
            log.debug("  Fetched batch {}/{} ({} books)",
                    i / BATCH_SIZE + 1,
                    (marketIds.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batchBooks.size());
        }
        log.info("Received {} market book(s)", books.size());

        // Step 4: Build snapshot
        BetfairOddsSnapshot snapshot = new BetfairOddsSnapshot(
                catalogue, books, Instant.now().toString());

        // Step 5: Serialize to JSON
        String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(snapshot);

        // Step 6: Save to local file
        Path outputPath = Path.of(config.outputPath);
        Files.writeString(outputPath, jsonPayload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Step 6: Snapshot saved to {}", outputPath.toAbsolutePath());

        // Step 7: Push to production (unless --no-push)
        if (!config.noPush) {
            pushOddsToProduction(jsonPayload, cloudflareJwt);
        } else {
            log.info("Step 7: Skipping production push (--no-push)");
        }
    }

    // ── Production Push ────────────────────────────────────────────────────

    /**
     * Pushes the odds JSON to the production server via the admin upload
     * endpoint. Authenticated with a Cloudflare Access JWT. Skipped silently
     * if the JWT is empty.
     */
    private static void pushOddsToProduction(String jsonPayload, String cloudflareJwt)
            throws Exception {

        if (cloudflareJwt == null || cloudflareJwt.isBlank()) {
            log.info("Step 7: Cloudflare JWT not configured - skipping push to production. "
                    + "Set ADMIN_CLOUDFLARE_JWT to enable automatic odds upload.");
            return;
        }

        log.info("Step 7: Pushing odds snapshot to production ({})...", PROD_ODDS_UPLOAD_URL);

        RestTemplate restClient = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Cookie", "CF_Authorization=" + cloudflareJwt);

        HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);

        ResponseEntity<String> response = restClient.exchange(
                PROD_ODDS_UPLOAD_URL, HttpMethod.POST, request, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("Successfully pushed odds to production - HTTP {}",
                    response.getStatusCode().value());
        } else if (response.getStatusCode().value() == 302) {
            throw new Exception(
                    "Production server returned 302 redirect - the Cloudflare JWT "
                            + "(ADMIN_CLOUDFLARE_JWT) is likely expired or invalid. "
                            + "Generate a fresh token by copying the CF_Authorization cookie "
                            + "from your browser's developer tools.");
        } else {
            throw new Exception(
                    "Production server returned non-2xx status: HTTP " +
                            response.getStatusCode().value() +
                            " - body: " + response.getBody());
        }
    }

    // ── Configuration ──────────────────────────────────────────────────────

    /**
     * Loads Betfair configuration from environment variables.
     *
     * @throws IllegalStateException if any required variable is missing
     */
    private static BetfairProperties loadConfigFromEnv() {
        String certPath = System.getenv("BETFAIR_CERT_PATH");
        String apiKey = System.getenv("BETFAIR_API_KEY");
        String username = System.getenv("BETFAIR_USERNAME");
        String password = System.getenv("BETFAIR_PASSWORD");

        List<String> missing = new ArrayList<>();
        if (certPath == null || certPath.isBlank()) missing.add("BETFAIR_CERT_PATH");
        if (apiKey == null || apiKey.isBlank()) missing.add("BETFAIR_API_KEY");
        if (username == null || username.isBlank()) missing.add("BETFAIR_USERNAME");
        if (password == null || password.isBlank()) missing.add("BETFAIR_PASSWORD");

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variables: " + String.join(", ", missing));
        }

        return new BetfairProperties(apiKey, username, password, certPath);
    }

    // ── Argument Parsing ───────────────────────────────────────────────────

    /**
     * Parses command-line arguments into an {@link AppConfig}.
     */
    private static AppConfig parseArgs(String[] args) {
        AppConfig config = new AppConfig();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> config.help = true;
                case "--loop" -> config.loop = true;
                case "--once" -> config.loop = false;
                case "--no-push" -> config.noPush = true;
                case "--interval" -> {
                    if (i + 1 < args.length) {
                        config.intervalMinutes = Integer.parseInt(args[++i]);
                    }
                }
                case "--initial-delay" -> {
                    if (i + 1 < args.length) {
                        config.initialDelayMinutes = Integer.parseInt(args[++i]);
                    }
                }
                case "--output" -> {
                    if (i + 1 < args.length) {
                        config.outputPath = args[++i];
                    }
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        log.warn("Unknown option: {}", args[i]);
                    }
                }
            }
        }

        return config;
    }

    /**
     * Prints usage information to stdout.
     */
    private static void printUsage() {
        System.out.println("""
                === Betfair Odds Snapshot Standalone App ===

                Usage: java -jar fifa-standalone.jar [options]

                Options:
                  --once                Run a single snapshot and exit (default)
                  --loop                Run continuously at fixed intervals
                  --interval <min>      Interval between snapshots in loop mode (default: 13)
                  --initial-delay <min> Delay before first snapshot in loop mode (default: 0)
                  --output <path>       Output file path (default: ./fallback-odds.json)
                  --no-push             Skip pushing to the production server
                  --help, -h            Show this help message

                Environment Variables:
                  BETFAIR_CERT_PATH     Path to directory containing ssl/ folder
                  BETFAIR_API_KEY       Betfair API key
                  BETFAIR_USERNAME      Betfair username
                  BETFAIR_PASSWORD      Betfair password
                  ADMIN_CLOUDFLARE_JWT  Cloudflare JWT for production upload (optional)

                Examples:
                  java -jar fifa-standalone.jar
                  java -jar fifa-standalone.jar --loop --interval 13
                  java -jar fifa-standalone.jar --output ./data/fallback-odds.json --no-push
                """);
    }

    // ── mTLS / SSL Setup (replicated from BetfairSslConfig) ────────────────

    /**
     * Builds a {@link RestTemplate} pre-configured with mutual TLS using the
     * Betfair client certificate and private key.
     */
    private static RestTemplate buildMtlsRestTemplate(BetfairProperties props,
            Duration connectTimeout,
            Duration readTimeout) {
        try {
            SSLContext sslContext = createSslContext(props);

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(connectTimeout)
                    .build();

            JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(readTimeout);

            return new RestTemplateBuilder()
                    .requestFactory(() -> requestFactory)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to create mTLS RestTemplate for Betfair", e);
        }
    }

    /**
     * Creates the mTLS {@link SSLContext} from the Betfair client certificate
     * and private key.
     */
    private static SSLContext createSslContext(BetfairProperties props) throws Exception {
        KeyStore ks = loadPkcs12Keystore(props);

        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

    /**
     * Creates an in-memory PKCS#12 keystore from the PEM cert + key files.
     */
    private static KeyStore loadPkcs12Keystore(BetfairProperties props) throws Exception {
        Path base = Path.of(props.certPath());
        Path certPath = base.resolve(CERT_FILE);
        Path keyPath = base.resolve(KEY_FILE);

        log.info("Loading Betfair client certificate from: {}", certPath.toAbsolutePath());
        log.info("Loading Betfair private key from: {}", keyPath.toAbsolutePath());

        if (!Files.exists(certPath)) {
            throw new IllegalStateException(
                    "Certificate file not found: " + certPath.toAbsolutePath());
        }
        if (!Files.exists(keyPath)) {
            throw new IllegalStateException(
                    "Private key file not found: " + keyPath.toAbsolutePath());
        }

        // Parse the X.509 certificate
        X509Certificate certificate;
        try (var in = Files.newInputStream(certPath)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) cf.generateCertificate(in);
        }

        // Parse the PEM private key using Bouncy Castle
        PrivateKey privateKey = parsePemPrivateKey(keyPath);

        // Build an in-memory PKCS#12 keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(
                "betfair-client",
                privateKey,
                KEYSTORE_PASSWORD.toCharArray(),
                new Certificate[] { certificate });

        log.info("Successfully loaded Betfair client certificate [subject={}, serial={}]",
                certificate.getSubjectX500Principal().getName(),
                certificate.getSerialNumber());

        return ks;
    }

    /**
     * Parses a PEM-encoded private key (PKCS#1 or PKCS#8) using Bouncy Castle.
     */
    private static PrivateKey parsePemPrivateKey(Path keyPath) throws IOException {
        try (var reader = Files.newBufferedReader(keyPath)) {
            PEMParser parser = new PEMParser(reader);
            Object obj = parser.readObject();
            if (obj == null) {
                throw new IllegalStateException("No private key found in: " + keyPath);
            }
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PrivateKeyInfo pki) {
                // PKCS#8 encoded key (BEGIN PRIVATE KEY)
                return converter.getPrivateKey(pki);
            }
            if (obj instanceof PEMKeyPair keyPair) {
                // PKCS#1 encoded key (BEGIN RSA PRIVATE KEY)
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            }
            throw new IllegalStateException(
                    "Unexpected PEM object type: " + obj.getClass().getName());
        }
    }

    // ── Config Record ──────────────────────────────────────────────────────

    /**
     * Mutable configuration holder populated from command-line arguments.
     */
    private static class AppConfig {
        boolean help = false;
        boolean loop = false;
        int intervalMinutes = DEFAULT_INTERVAL_MINUTES;
        int initialDelayMinutes = 0;
        String outputPath = "./fallback-odds.json";
        boolean noPush = false;
    }
}