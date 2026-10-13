package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** One independent faculty for one typed resource; no academic/student authority. */
public record ResourceManagerGrant(long id, long institutionId, Kind kind, long resourceId,
        long membershipId, Faculty faculty, Status status, Instant validFrom, Instant validUntil,
        Instant grantedAt, long grantedBy, long grantorMembershipId, Instant revokedAt,
        Long revokedBy, Long revokerMembershipId, String reason) {
    public enum Kind { CONCEPT, EVALUATION }
    public enum Faculty { EDIT_DRAFT, PUBLISH, ASSIGN }
    public enum Status { ACTIVE, REVOKED }
}
