package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean temporaryPassword = true;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long credentialsVersion;

    public void invalidateSessions() { credentialsVersion = Math.addExact(credentialsVersion, 1); }

    // Keep credential invalidation at the entity boundary, including future role changes.
    public void setPassword(String value) {
        if (!java.util.Objects.equals(password, value)) invalidateSessions();
        password = value;
    }
    public void setActive(Boolean value) {
        if (!java.util.Objects.equals(active, value)) invalidateSessions();
        active = value;
    }
    public void setRole(Role value) {
        if (role != value) invalidateSessions();
        role = value;
    }
    public void setTemporaryPassword(Boolean value) {
        if (!java.util.Objects.equals(temporaryPassword, value)) invalidateSessions();
        temporaryPassword = value;
    }
    public void setUsername(String value) {
        if (!java.util.Objects.equals(username, value)) invalidateSessions();
        username = value;
    }

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
