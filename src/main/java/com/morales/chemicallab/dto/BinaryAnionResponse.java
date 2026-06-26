package com.morales.chemicallab.dto;

/**
 * Anión monoatómico de una sal binaria.
 *
 * Expone los datos que el frontend necesita para armar el selector y el
 * {@code SaltRequest}: el símbolo del no metal, su nombre de anión y la carga
 * (valor absoluto). El motor químico se encarga de cruzar las cargas.
 */
public record BinaryAnionResponse(
        String symbol,
        String name,
        int charge
) {
}
