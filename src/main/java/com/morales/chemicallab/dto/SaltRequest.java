package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Petición para formar una sal binaria.
 *
 * El cliente solo envía referencias al catálogo (símbolo del metal, su valencia
 * y el símbolo del no metal); el backend valida contra el catálogo y deriva el
 * nombre del metal, el nombre del anión y su carga. Así el frontend no necesita
 * mantener cargas ni nombres propios.
 */
public record SaltRequest(
        @NotBlank(message = "El símbolo del metal es obligatorio")
        String metalSymbol,

        @NotNull(message = "La valencia del metal es obligatoria")
        @Min(value = 1, message = "La valencia del metal debe ser mayor o igual a 1")
        Integer metalValence,

        @NotBlank(message = "El símbolo del no metal es obligatorio")
        String nonMetalSymbol
) {
}
