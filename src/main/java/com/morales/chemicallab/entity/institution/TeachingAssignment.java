package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal JDBC snapshot; status alone never grants academic access. */
public record TeachingAssignment(long id, long institutionId, long classId, long membershipId, Instant validFrom, Instant validUntil, Instant activatedAt, Instant endedAt, Status status, Instant stateSince, long version) {
    public enum Status { PLANNED, ACTIVE, ENDED, CANCELLED }
}
