package dev.scaffoldkit.fifa.controller;

import dev.scaffoldkit.fifa.model.UserProfile;
import dev.scaffoldkit.fifa.repository.UserProfileRepository;
import dev.scaffoldkit.fifa.web.UserContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public UserStateController(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @GetMapping("/state")
    public ResponseEntity<String> getState() {
        UserProfile profile = UserContext.get();
        String json = (profile != null && profile.getPredictionsJson() != null)
                ? profile.getPredictionsJson()
                : "{}";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/state")
    public ResponseEntity<Void> saveState(@RequestBody String rawJson) {
        UserProfile profile = UserContext.get();
        if (profile == null) {
            return ResponseEntity.status(401).build();
        }
        profile.setPredictionsJson(rawJson);
        profile.setUpdatedAt(Instant.now());
        userProfileRepository.save(profile);
        return ResponseEntity.ok().build();
    }
}