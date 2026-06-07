package dev.scaffoldkit.fifa.betfair;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Communicates with the Betfair Exchange Betting REST API.
 *
 * <p>Provides methods to:
 * <ul>
 *   <li>{@link #listMarketCatalogue(String)} — discover soccer match-odds markets</li>
 *   <li>{@link #listMarketBook(String, List)} — fetch current odds for specific markets</li>
 * </ul>
 */
@Component
class BetfairMarketClient {

    private static final Logger log = LoggerFactory.getLogger(BetfairMarketClient.class);

    private static final String BASE_URL = "https://api.betfair.com/exchange/betting/rest/v1.0";
    private static final String CATALOGUE_URL = BASE_URL + "/listMarketCatalogue/";
    private static final String BOOK_URL = BASE_URL + "/listMarketBook/";

    /** Betfair Soccer Event Type ID */
    private static final String SOCCER_EVENT_TYPE_ID = "1";

    private final BetfairProperties properties;
    private final RestTemplate apiRestTemplate;
    private final ObjectMapper objectMapper;

    BetfairMarketClient(BetfairProperties properties,
                        @Qualifier("betfairApiRestTemplate") RestTemplate apiRestTemplate) {
        this.properties = properties;
        this.apiRestTemplate = apiRestTemplate;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Fetches the market catalogue for soccer match-odds markets.
     *
     * @param sessionToken the active Betfair session token
     * @return raw JSON string of the market catalogue response
     */
    String listMarketCatalogue(String sessionToken) {
        log.info("Fetching market catalogue for soccer match odds");

        HttpHeaders headers = apiHeaders(sessionToken);

        Map<String, Object> filter = Map.of(
                "eventTypeIds", List.of(SOCCER_EVENT_TYPE_ID)
        );

        // Request a reasonable set of markets with competition/event info
        Map<String, Object> body = Map.of(
                "filter", filter,
                "marketTypeCodes", List.of("MATCH_ODDS"),
                "maxResults", 100,
                "marketProjection", List.of(
                        "COMPETITION", "EVENT", "EVENT_TYPE",
                        "MARKET_START_TIME", "RUNNER_DESCRIPTION"
                )
        );

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = apiRestTemplate.exchange(
                    CATALOGUE_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("Market catalogue response received ({} chars)",
                        response.getBody().length());
                return response.getBody();
            }

            log.error("listMarketCatalogue returned status: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch market catalogue", e);
            return null;
        }
    }

    /**
     * Fetches the current market book (odds) for the given market IDs.
     *
     * @param sessionToken the active Betfair session token
     * @param marketIds    the market IDs to fetch prices for
     * @return raw JSON string of the market book response
     */
    String listMarketBook(String sessionToken, List<String> marketIds) {
        log.info("Fetching market book for {} market(s)", marketIds.size());

        HttpHeaders headers = apiHeaders(sessionToken);

        Map<String, Object> body = Map.of(
                "marketIds", marketIds,
                "priceProjection", Map.of(
                        "priceData", List.of("EX_BEST_OFFERS"),
                        "exBestOffersOverrides", Map.of("bestPricesDepth", 3)
                )
        );

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = apiRestTemplate.exchange(
                    BOOK_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("Market book response received ({} chars)",
                        response.getBody().length());
                return response.getBody();
            }

            log.error("listMarketBook returned status: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch market book", e);
            return null;
        }
    }

    /**
     * Builds the standard headers required by the Betfair Exchange API.
     */
    private HttpHeaders apiHeaders(String sessionToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set("X-Application", properties.apiKey());
        headers.set("X-Authentication", sessionToken);
        return headers;
    }
}