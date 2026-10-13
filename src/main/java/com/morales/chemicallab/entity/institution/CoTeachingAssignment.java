package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal persisted CO_DOCENTE episode. This snapshot is never an access authority. */
public record CoTeachingAssignment(long id, long institutionId, long classId, long membershipId,
        Instant validFrom, Instant validUntil, Instant activatedAt, Instant endedAt,
        TeachingAssignment.Status status, Instant stateSince, long version) {}
