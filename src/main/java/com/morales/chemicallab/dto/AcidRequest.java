package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para formar un ácido.
 *
 * Según {@code acidType}:
 * <ul>
 *   <li>{@code HYDRACID}: se usa {@code nonMetalSymbol} (catálogo de no metales
 *       de ácido) y el backend deriva la carga.</li>
 *   <li>{@code OXOACID}: se usa {@code oxoanionKey} (catálogo de oxoaniones) y
 *       el backend deriva fórmula y carga.</li>
 * </ul>
 * No se implementa todavía la nomenclatura tradicional/sistemática/Stock: solo
 * se devuelve la fórmula y una explicación básica.
 */
public record AcidRequest(
        @NotNull(message = "El tipo de ácido es obligatorio (HYDRACID u OXOACID)")
        AcidType acidType,

        String nonMetalSymbol,

        String oxoanionKey
) {
}
