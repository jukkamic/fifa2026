package dev.scaffoldkit.fifa.betfair;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Builds {@link RestTemplate} instances pre-configured with mutual TLS using
 * the Betfair client certificate and private key loaded from the filesystem.
 *
 * <p>Two beans are provided:
 * <ul>
 *   <li>{@code betfairAuthRestTemplate} — targets the SSO certlogin endpoint
 *       with relaxed timeouts.</li>
 *   <li>{@code betfairApiRestTemplate} — targets the Exchange betting API.</li>
 * </ul>
 */
@Configuration
@Profile("!prod")
class BetfairSslConfig {

    private static final Logger log = LoggerFactory.getLogger(BetfairSslConfig.class);

    private static final String CERT_FILE = "ssl/client-2048.crt";
    private static final String KEY_FILE  = "ssl/client-2048.key";
    private static final String KEYSTORE_PASSWORD = "betfair";

    /**
     * Creates an in-memory PKCS#12 keystore from the PEM cert + key files.
     */
    private KeyStore loadPkcs12Keystore(BetfairProperties props) throws Exception {
        Path base = Path.of(props.certPath());
        Path certPath = base.resolve(CERT_FILE);
        Path keyPath  = base.resolve(KEY_FILE);

        log.info("Loading Betfair client certificate from: {}", certPath.toAbsolutePath());
        log.info("Loading Betfair private key from: {}", keyPath.toAbsolutePath());

        if (!Files.exists(certPath)) {
            throw new IllegalStateException("Certificate file not found: " + certPath.toAbsolutePath());
        }
        if (!Files.exists(keyPath)) {
            throw new IllegalStateException("Private key file not found: " + keyPath.toAbsolutePath());
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
                new Certificate[]{ certificate }
        );

        log.info("Successfully loaded Betfair client certificate [subject={}, serial={}]",
                certificate.getSubjectX500Principal().getName(),
                certificate.getSerialNumber());

        return ks;
    }

    /**
     * Parses a PEM-encoded private key (PKCS#1 or PKCS#8) using Bouncy Castle.
     */
    private PrivateKey parsePemPrivateKey(Path keyPath) throws IOException {
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
                // PKCS#1 encoded key (BEGIN RSA PRIVATE KEY) — produced by openssl genrsa
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            }
            throw new IllegalStateException("Unexpected PEM object type: " + obj.getClass().getName());
        }
    }

    /**
     * Shared SSL context configured for mutual TLS.
     */
    private SSLContext createSslContext(BetfairProperties props) throws Exception {
        KeyStore ks = loadPkcs12Keystore(props);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

    /**
     * Builds a {@link RestTemplate} pre-wired with the mTLS HttpClient.
     */
    private RestTemplate buildMtlsRestTemplate(BetfairProperties props, Duration connectTimeout, Duration readTimeout) {
        try {
            SSLContext sslContext = createSslContext(props);

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(connectTimeout)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(readTimeout);

            return new RestTemplateBuilder()
                    .requestFactory(() -> requestFactory)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create mTLS RestTemplate for Betfair", e);
        }
    }

    @Bean("betfairAuthRestTemplate")
    RestTemplate betfairAuthRestTemplate(BetfairProperties props) {
        return buildMtlsRestTemplate(props, Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Bean("betfairApiRestTemplate")
    RestTemplate betfairApiRestTemplate(BetfairProperties props) {
        return buildMtlsRestTemplate(props, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }
}