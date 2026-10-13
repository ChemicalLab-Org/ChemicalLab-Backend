package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal JDBC snapshot; status alone never grants academic access. */
public record AcademicClass(long id, long institutionId, long periodId, String localCode, String name, String course, String grade, String section, Status status, Instant stateSince, long version) {
    public enum Status { PLANNED, ACTIVE, CLOSED, ARCHIVED }
}
