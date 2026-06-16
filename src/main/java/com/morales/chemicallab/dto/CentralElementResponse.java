package com.morales.chemicallab.dto;

/**
 * Elemento central de un oxoanión (p. ej. "S" en sulfato/sulfito).
 *
 * Permite al frontend ofrecer primero el elemento central y luego filtrar los
 * grupos oxácidos disponibles para ese elemento, sin mantener esa relación de
 * forma manual.
 */
public record CentralElementResponse(
        String symbol,
        String name
) {
}
