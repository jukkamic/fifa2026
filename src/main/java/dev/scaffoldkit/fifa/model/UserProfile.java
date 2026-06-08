package dev.scaffoldkit.fifa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Lob
    @Column(name = "predictions_json", columnDefinition = "CLOB")
    private String predictionsJson;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected UserProfile() {
        // JPA
    }

    public UserProfile(String email, String predictionsJson) {
        this.email = email;
        this.predictionsJson = predictionsJson;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPredictionsJson() {
        return predictionsJson;
    }

    public void setPredictionsJson(String predictionsJson) {
        this.predictionsJson = predictionsJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}