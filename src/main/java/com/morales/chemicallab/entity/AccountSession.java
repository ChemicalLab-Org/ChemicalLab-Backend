package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_sessions", indexes = {
        @Index(name = "idx_account_sessions_user", columnList = "user_id"),
        @Index(name = "idx_account_sessions_expiry", columnList = "expires_at")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountSession {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;
    @Column(nullable = false)
    private long credentialsVersion;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant revokedAt;
}
