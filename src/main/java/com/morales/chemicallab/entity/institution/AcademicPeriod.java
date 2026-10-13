package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal JDBC snapshot; status alone never grants academic access. */
public record AcademicPeriod(long id, long institutionId, String localCode, String name, Instant validFrom, Instant validUntil, Status status, Instant stateSince, long version) {
    public enum Status { PLANNED, OPEN, CLOSED, ARCHIVED }
}
