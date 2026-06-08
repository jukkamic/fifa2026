package dev.scaffoldkit.fifa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.repository.UserProfileRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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

    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    public UserStateController(UserProfileRepository userProfileRepository,
                               ObjectMapper objectMapper) {
        this.userProfileRepository = userProfileRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/state")
    public ResponseEntity<String> getState(@AuthenticationPrincipal UserProfile profile) throws Exception {
        String email = profile.getEmail();
        String stateJson = (profile.getPredictionsJson() != null)
                ? profile.getPredictionsJson()
                : "{}";

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("email", email);
        wrapper.set("state", objectMapper.readTree(stateJson));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(wrapper));
    }

    @PostMapping("/state")
    public ResponseEntity<Void> saveState(@RequestBody String rawJson,
                                          @AuthenticationPrincipal UserProfile profile) {
        profile.setPredictionsJson(rawJson);
        profile.setUpdatedAt(Instant.now());
        userProfileRepository.save(profile);
        return ResponseEntity.ok().build();
    }
}