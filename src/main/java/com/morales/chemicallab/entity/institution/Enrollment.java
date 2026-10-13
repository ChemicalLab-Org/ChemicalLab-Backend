package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal JDBC snapshot; status alone never grants academic access. */
public record Enrollment(long id, long institutionId, long classId, long membershipId, int episodeNumber, Instant validFrom, Instant validUntil, Instant activatedAt, Instant endedAt, Status status, Instant stateSince, long version) {
    public enum Status { PENDING, ACTIVE, SUSPENDED, COMPLETED, WITHDRAWN, TRANSFERRED, CANCELLED }
}
