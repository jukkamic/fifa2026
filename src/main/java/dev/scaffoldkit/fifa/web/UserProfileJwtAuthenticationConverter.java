package dev.scaffoldkit.fifa.web;

import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.repository.UserProfileRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts a validated JWT into a Spring Security authentication whose
 * principal is a {@link UserProfile} looked up (or auto-created) by the
 * JWT's {@code email} claim — the claim Cloudflare Access injects.
 */
@Component
public class UserProfileJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserProfileRepository userProfileRepository;

    public UserProfileJwtAuthenticationConverter(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("JWT does not contain an email claim");
        }

        UserProfile profile = userProfileRepository
                .findByEmail(email)
                .orElseGet(() -> userProfileRepository.save(new UserProfile(email, "{}")));

        return new UserProfileAuthenticationToken(profile, jwt.getTokenValue());
    }

    /**
     * Custom authentication token that carries a {@link UserProfile} as the principal.
     */
    static class UserProfileAuthenticationToken extends AbstractAuthenticationToken {

        private final UserProfile profile;
        private final String tokenValue;

        UserProfileAuthenticationToken(UserProfile profile, String tokenValue) {
            super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
            this.profile = profile;
            this.tokenValue = tokenValue;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return tokenValue;
        }

        @Override
        public Object getPrincipal() {
            return profile;
        }
    }
}