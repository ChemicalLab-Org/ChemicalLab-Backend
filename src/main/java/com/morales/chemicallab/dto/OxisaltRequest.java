package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Petición para formar una oxisal.
 *
 * El cliente envía el símbolo del metal, su valencia y la clave del oxoanión;
 * el backend valida contra el catálogo y deriva la fórmula del grupo, su carga,
 * su nombre y el nombre del metal. El frontend no necesita conocer la fórmula
 * ni la carga del grupo.
 */
public record OxisaltRequest(
        @NotBlank(message = "El símbolo del metal es obligatorio")
        String metalSymbol,

        @NotNull(message = "La valencia del metal es obligatoria")
        @Min(value = 1, message = "La valencia del metal debe ser mayor o igual a 1")
        Integer metalValence,

        @NotBlank(message = "La clave del grupo oxácido es obligatoria")
        String oxoanionKey
) {
}
