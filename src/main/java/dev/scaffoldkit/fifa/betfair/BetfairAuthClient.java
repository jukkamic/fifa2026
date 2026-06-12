package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Handles the Betfair non-interactive (mutual-TLS) login flow against
 * {@code https://identitysso-cert.betfair.com/api/certlogin}.
 *
 * <p>Returns a session token required by all subsequent API calls.
 */
@Component
@Profile("!prod")
class BetfairAuthClient {

    private static final Logger log = LoggerFactory.getLogger(BetfairAuthClient.class);
    private static final String LOGIN_URL = "https://identitysso-cert.betfair.com/api/certlogin";

    private final BetfairProperties properties;
    private final RestTemplate authRestTemplate;
    private final ObjectMapper objectMapper;

    BetfairAuthClient(BetfairProperties properties,
                      @Qualifier("betfairAuthRestTemplate") RestTemplate authRestTemplate) {
        this.properties = properties;
        this.authRestTemplate = authRestTemplate;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Executes the certlogin request and returns the session token.
     *
     * @return the Betfair session token, or {@code null} if authentication failed
     */
    String login() {
        log.info("Authenticating with Betfair via certlogin as user: {}", properties.username());

        // Headers
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set("X-Application", properties.apiKey());

        // Form-encoded body
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("username", properties.username());
        body.add("password", properties.password());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = authRestTemplate.exchange(
                    LOGIN_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseLoginResponse(response.getBody());
            }

            log.error("Betfair certlogin returned non-success status: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("Betfair certlogin request failed", e);
            return null;
        }
    }

    private String parseLoginResponse(String json) {
        try {
            var tree = objectMapper.readTree(json);
            String status = tree.path("loginStatus").asText("");

            if ("SUCCESS".equalsIgnoreCase(status)) {
                String sessionToken = tree.path("sessionToken").asText(null);
                if (sessionToken != null) {
                    log.info("Betfair login successful – session token acquired (length={})",
                            sessionToken.length());
                    return sessionToken;
                }
                log.error("Betfair login succeeded but no session token in response body");
            } else {
                log.error("Betfair login failed with status: {} – {}", status, json);
            }
        } catch (Exception e) {
            log.error("Failed to parse Betfair login response", e);
        }
        return null;
    }
}