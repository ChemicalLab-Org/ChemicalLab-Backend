package com.morales.chemicallab.entity.institution;

import java.time.Instant;

/** Internal observation only: payload includes private questions/material bytes. Never a REST DTO. */
public record AcademicRevision(long id, long resourceId, long institutionId, Long parentId,
                               int number, long version, String status, String payload,
                               String checksum, Instant publishedAt) {}
