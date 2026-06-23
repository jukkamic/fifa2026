package dev.scaffoldkit.fifa.betfair;

import dev.scaffoldkit.fifa.betfair.model.BetfairMarketCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the Betfair API connection works end-to-end.
 *
 * <p>This test uses real credentials from the {@code .env} file and makes
 * actual HTTP calls to the Betfair API. It requires:
 * <ul>
 *   <li>Valid Betfair credentials (api-key, username, password) in {@code .env}</li>
 *   <li>Valid SSL client certificate at the configured cert path</li>
 * </ul>
 *
 * <p>Run with: {@code .\gradlew.bat test --tests "dev.scaffoldkit.fifa.betfair.BetfairConnectionTest"}
 */
@SpringBootTest
@Tag("integration")
class BetfairConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(BetfairConnectionTest.class);
    private static final String LOGIN_URL = "https://identitysso-cert.betfair.com/api/certlogin";

    @Autowired
    private BetfairAuthClient authClient;

    @Autowired
    private BetfairMarketClient marketClient;

    @Autowired
    private BetfairProperties properties;

    @Autowired
    @Qualifier("betfairAuthRestTemplate")
    private RestTemplate authRestTemplate;

    @Test
    void shouldConnectToBetfairViaMutualTls() {
        // Makes a real HTTPS call to Betfair using the mTLS RestTemplate.
        // Any HTTP response (even a login error) proves the connection works:
        // DNS resolved, TCP connected, TLS handshake with client cert succeeded.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set("X-Application", properties.apiKey());

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("username", properties.username());
        body.add("password", properties.password());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = authRestTemplate.exchange(
                LOGIN_URL, HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode().value())
                .as("Betfair should return an HTTP response")
                .isBetween(200, 599);

        assertThat(response.getBody())
                .as("Response body should contain a loginStatus field")
                .isNotNull()
                .contains("loginStatus");

        log.info("Betfair connection OK - HTTP {} - body: {}", response.getStatusCode(), response.getBody());
    }

    @Test
    void shouldAuthenticateWithValidCredentials() {
        // This test requires valid, fully-active Betfair credentials.
        // It will fail if the account has issues (e.g. pending password change).
        String sessionToken = authClient.login();

        assertThat(sessionToken)
                .as("Betfair login should return a session token. " +
                        "If null, check logs - common causes: wrong credentials, " +
                        "ACCOUNT_PENDING_PASSWORD_CHANGE, expired certificate")
                .isNotNull();

        log.info("Betfair authentication OK - session token acquired");
    }

    @Test
    void shouldFetchMarketCatalogueAfterAuth() {
        // Requires successful authentication first
        String sessionToken = authClient.login();
        assertThat(sessionToken)
                .as("Cannot test market catalogue without a valid session token")
                .isNotNull();

        List<BetfairMarketCatalog> catalogue = marketClient.listMarketCatalogue(sessionToken);

        assertThat(catalogue)
                .as("Market catalogue should return a non-empty list of markets")
                .isNotEmpty();

        assertThat(catalogue.get(0).marketId())
                .as("First market should have a marketId")
                .isNotBlank();

        log.info("Market catalogue fetched successfully - {} markets", catalogue.size());
    }

    @Test
    void sslConfigurationShouldLoadSuccessfully() {
        // The Spring context starting up proves that:
        // 1. SSL cert and key files exist at the configured path
        // 2. They were parsed correctly (X.509 cert + PEM private key)
        // 3. The mTLS RestTemplate beans were created with the SSL context
        assertThat(authClient).isNotNull();
        assertThat(marketClient).isNotNull();
        assertThat(properties.apiKey()).isNotBlank();
        assertThat(properties.username()).isNotBlank();

        log.info("SSL configuration loaded - mTLS RestTemplates created successfully");
    }
}