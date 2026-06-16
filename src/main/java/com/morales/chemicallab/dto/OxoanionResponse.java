package com.morales.chemicallab.dto;

/**
 * Oxoanión (grupo oxácido poliatómico) para formar oxisales y oxácidos.
 *
 * Expone los datos que el frontend necesita para armar el selector y la
 * petición: una clave estable, el nombre del grupo, su fórmula, la carga (valor
 * absoluto) y el elemento central. El motor químico se encarga de cruzar las
 * cargas y de usar paréntesis cuando el subíndice del grupo es mayor a 1.
 */
public record OxoanionResponse(
        String key,
        String name,
        String formula,
        int charge,
        String centralElement
) {
}
