package dev.scaffoldkit.fifa.controller;

import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.repository.UserProfileRepository;
import dev.scaffoldkit.fifa.service.ActualResultsService;

/**
 * REST API for persisting per-user tournament state.
 *
 * Endpoints:
 *   GET  /api/user/state  — returns predictionsJson for the current user (or {})
 *   POST /api/user/state  — saves raw JSON body as predictionsJson for the current user
 */
@RestController
@RequestMapping("/api/user")
public class UserStateController {

    private final String adminEmail;
    private final String devAdminEmail;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private final ActualResultsService actualResultsService;

    public UserStateController(
            @Value("${app.admin.email:jukkamic@gmail.com}") String adminEmail,
            @Value("${app.admin.dev-email:testuser@example.com}") String devAdminEmail,
            UserProfileRepository userProfileRepository,
            ObjectMapper objectMapper,
            ActualResultsService actualResultsService) {
        this.adminEmail = adminEmail;
        this.devAdminEmail = devAdminEmail;
        this.userProfileRepository = userProfileRepository;
        this.objectMapper = objectMapper;
        this.actualResultsService = actualResultsService;
    }

    @GetMapping("/state")
    public ResponseEntity<String> getState(@AuthenticationPrincipal UserProfile profile) throws Exception {
        String email = profile.getEmail();
        String stateJson = (profile.getPredictionsJson() != null)
                ? profile.getPredictionsJson()
                : "{}";

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("email", email);
        wrapper.put("isAdmin", adminEmail.equals(email) || devAdminEmail.equals(email));
        wrapper.set("state", objectMapper.readTree(stateJson));

        // Include locked matches
        ObjectNode lockedNode = objectMapper.createObjectNode();
        for (var entry : actualResultsService.getLockedScores().entrySet()) {
            ArrayNode scores = objectMapper.createArrayNode();
            scores.add(entry.getValue()[0]);
            scores.add(entry.getValue()[1]);
            lockedNode.set(entry.getKey(), scores);
        }
        wrapper.set("lockedMatches", lockedNode);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(wrapper));
    }

    @PostMapping("/state")
    public ResponseEntity<Void> saveState(@RequestBody String rawJson,
                                          @AuthenticationPrincipal UserProfile profile) {
        // The authentication filter may create a transient UserProfile (id=null)
        // on every request (e.g. MockAuthenticationFilter in dev). If a row already
        // exists for this email we must load the managed entity so that JPA performs
        // an UPDATE instead of an INSERT, which would violate the unique email constraint.
        Optional<UserProfile> existing = userProfileRepository.findByEmail(profile.getEmail());
        UserProfile managed = existing.orElse(profile);
        managed.setPredictionsJson(rawJson);
        managed.setUpdatedAt(Instant.now());
        userProfileRepository.save(managed);
        return ResponseEntity.ok().build();
    }
}