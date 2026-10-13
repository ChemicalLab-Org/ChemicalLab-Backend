package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Stable account/institution identity; readmission reuses this ID. */
public record InstitutionMembership(long id, long institutionId, long accountId, Status status,
                                    Instant validFrom, Instant validUntil, Instant stateSince, long accessVersion) {
    public enum Status { PENDING, ACTIVE, SUSPENDED, ENDED }
}
