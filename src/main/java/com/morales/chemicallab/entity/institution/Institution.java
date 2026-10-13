package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** SQL-owned persistent entity. Deliberately outside Hibernate schema generation. */
public record Institution(long id, String platformCode, String name, String timezone,
                          Status status, long accessVersion, Instant createdAt, Instant updatedAt) {
    public enum Status { DRAFT, ACTIVE, SUSPENDED, ARCHIVED }
}
