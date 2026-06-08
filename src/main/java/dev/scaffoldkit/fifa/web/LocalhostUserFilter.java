package dev.scaffoldkit.fifa.web;

import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.repository.UserProfileRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Simulates an authenticated user on localhost.
 * <p>
 * Looks up (or creates) a {@link UserProfile} for the hardcoded email
 * {@code testuser@example.com} and places it into {@link UserContext}
 * so controllers can access the current user via {@link UserContext#get()}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalhostUserFilter extends OncePerRequestFilter {

    private static final String HARDCODED_EMAIL = "testuser@example.com";

    private final UserProfileRepository userProfileRepository;

    public LocalhostUserFilter(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        UserProfile profile = userProfileRepository
                .findByEmail(HARDCODED_EMAIL)
                .orElseGet(() -> {
                    UserProfile created = new UserProfile(HARDCODED_EMAIL, "{}");
                    return userProfileRepository.save(created);
                });

        try {
            UserContext.set(profile);
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}