package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** F05: the institution/code remains owned by this account after membership ENDED. */
public record AcademicCodeReservation(long id, long institutionId, long membershipId,
        long accountId, String code, Instant reservedAt) { }
