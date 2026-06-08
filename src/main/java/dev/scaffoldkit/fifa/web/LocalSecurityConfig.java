package dev.scaffoldkit.fifa.web;

import dev.scaffoldkit.fifa.model.UserProfile;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security configuration for local development.
 * <p>
 * Active when the {@code prod} profile is <b>not</b> set.
 * Allows all requests without JWT validation and injects a mock
 * {@link UserProfile} for {@code testuser@example.com} so controllers
 * can use {@code @AuthenticationPrincipal UserProfile} the same way
 * they do in production.
 */
@Configuration
@EnableWebSecurity
@Profile("!prod")
public class LocalSecurityConfig {

    private static final String MOCK_EMAIL = "testuser@example.com";

    @Bean
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            )
            .addFilterBefore(new MockAuthenticationFilter(),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Filter that creates a mock {@link UserProfile} authentication for every request,
     * so that controllers reading {@code @AuthenticationPrincipal UserProfile} work
     * identically to the production Cloudflare JWT flow.
     */
    static class MockAuthenticationFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            UserProfile mockProfile = new UserProfile(MOCK_EMAIL, "{}");
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            mockProfile,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        }
    }
}