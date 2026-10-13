package com.morales.chemicallab.entity.institution;

/** Raw legacy alias plus explicit comparison policy. EMAIL is always unverified. */
public record AccountLoginIdentifier(long id, long accountId, Kind kind, String value,
        String comparisonValue, boolean verified, String status, boolean sourceCurrent) {
    public enum Kind { USERNAME, EMAIL }
}
