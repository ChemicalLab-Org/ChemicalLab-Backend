package com.morales.chemicallab.dto;

/**
 * Oxoanión (grupo oxácido poliatómico) para formar oxisales.
 *
 * Expone los datos que el frontend necesita para armar el selector y el
 * {@code OxisaltRequest}: una clave estable, el nombre del grupo, su fórmula y
 * la carga (valor absoluto). El motor químico se encarga de cruzar las cargas y
 * de usar paréntesis cuando el subíndice del grupo es mayor a 1.
 */
public record OxoanionResponse(
        String key,
        String name,
        String formula,
        int charge
) {
}
