package com.morales.chemicallab.dto;

/**
 * Respuesta del motor químico para un compuesto formado.
 *
 * Mantiene los campos previos ({@code valid}, {@code compoundType},
 * {@code formula}, {@code name}, {@code explanation}) e incorpora la
 * {@link NomenclatureResponse} con las tres nomenclaturas (tradicional, Stock y
 * sistemática). El campo {@code name} sigue siendo el nombre base de referencia
 * para no romper la compatibilidad con clientes existentes.
 */
public record CompoundResponse(
        boolean valid,
        String compoundType,
        String formula,
        String name,
        String explanation,
        NomenclatureResponse nomenclature
) {
}
