package dev.scaffoldkit.fifa.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for Cloudflare Zero Trust JWT validation.
 * <p>
 * Every request to {@code /api/**} must carry a valid Cloudflare Access JWT.
 * The JWT is validated against Cloudflare's public JWK set, then converted to
 * a {@link dev.scaffoldkit.fifa.model.UserProfile}-backed principal via
 * {@link UserProfileJwtAuthenticationConverter}.
 * <p>
 * Active only when the {@code prod} Spring profile is set.
 */
@Configuration
@EnableWebSecurity
@Profile("prod")
public class ProdSecurityConfig {

    private final UserProfileJwtAuthenticationConverter converter;

    public ProdSecurityConfig(UserProfileJwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            );

        return http.build();
    }

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        return request -> request.getHeader("Cf-Access-Jwt-Assertion");
    }
}