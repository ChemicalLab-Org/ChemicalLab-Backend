package com.morales.chemicallab.dto;

public record CompoundResponse(
        boolean valid,
        String compoundType,
        String formula,
        String name,
        String explanation
) {
}