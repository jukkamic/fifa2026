package dev.scaffoldkit.fifa.betfair;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the Betfair Exchange API integration.
 * Values are bound from environment variables declared in {@code .env}.
 */
@Validated
@ConfigurationProperties(prefix = "betfair")
public record BetfairProperties(

        @NotBlank String apiKey,

        @NotBlank String username,

        @NotBlank String password,

        /** Root directory that contains the {@code ssl/} certificate folder. */
        @NotBlank String certPath
) {}