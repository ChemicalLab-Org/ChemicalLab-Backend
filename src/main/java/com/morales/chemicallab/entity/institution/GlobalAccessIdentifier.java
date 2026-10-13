package com.morales.chemicallab.entity.institution;

import java.time.Instant;
import java.util.UUID;

/** Persisted P02 state. Not consumed by the legacy authentication resolver. */
public record GlobalAccessIdentifier(long id, long accountId, Kind kind, String value,
        Status status, long version, UUID operationId, Instant activatedAt, Instant terminatedAt, Long proofId,
        Long replacesId, Long replacesVersion) {
    public enum Kind { USERNAME, EMAIL }
    public enum Status { PREPARED, ACTIVE, RESERVED, REVOKED }
    public enum Origin { LEGACY, CHANGE, LINK }
}
