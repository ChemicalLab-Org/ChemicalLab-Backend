package com.morales.chemicallab.entity;

import jakarta.persistence.*;

/** JDBC owns atomic counter updates; mapping supplies the existing ddl-auto upgrade path. */
@Entity
@Table(name = "authentication_limits", indexes = @Index(name = "idx_auth_limits_expiry", columnList = "expires_at"))
public class AuthenticationLimit {
    @Id @Column(length = 100) private String bucket;
    @Column(nullable = false) private int attempts;
    @Column(name = "expires_at", nullable = false) private long expiresAt;
}
