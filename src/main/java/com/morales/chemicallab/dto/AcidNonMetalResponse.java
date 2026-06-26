package com.morales.chemicallab.dto;

/**
 * No metal que forma un ácido hidrácido al combinarse con hidrógeno.
 *
 * Expone los datos que el frontend necesita para el selector de ácidos
 * hidrácidos: el símbolo del no metal, el nombre de su anión y la carga (valor
 * absoluto). Es un subconjunto de los no metales: solo los halógenos (-1) y
 * anfígenos (-2) que forman hidrácido habitual en el nivel escolar.
 */
public record AcidNonMetalResponse(
        String symbol,
        String name,
        int charge
) {
}
