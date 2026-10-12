package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** These roles confer no current REST/STOMP authority. Platform ADMINISTRADOR is excluded. */
public record MembershipRoleGrant(long id, long membershipId, long institutionId, InstitutionalRole role,
                                  Status status, Instant validFrom, Instant validUntil,
                                  Long grantedBy, Long revokedBy, Instant revokedAt, String revocationReason) {
    public enum InstitutionalRole { ADMIN_INSTITUCION, DOCENTE, ESTUDIANTE }
    public enum Status { ACTIVE, REVOKED }
}
