package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Prepared platform authority, never consumed by T02/T04 or institution grants. */
public record PlatformRoleGrant(long id, long accountId, PlatformRole role, Status status,
        Instant validFrom, Instant validUntil, long proofId, Long grantedBy,
        Instant revokedAt, Long revokedBy, String revocationReason) {
    public enum PlatformRole { ADMINISTRADOR }
    public enum Status { ACTIVE, REVOKED }
}
