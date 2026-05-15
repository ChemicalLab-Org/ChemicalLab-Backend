package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SaltRequest(
        @NotBlank(message = "El símbolo del metal es obligatorio")
        String metalSymbol,

        @NotBlank(message = "El nombre del metal es obligatorio")
        String metalName,

        @NotNull(message = "La valencia del metal es obligatoria")
        @Min(value = 1, message = "La valencia del metal debe ser mayor o igual a 1")
        Integer metalValence,

        @NotBlank(message = "La fórmula del no metal es obligatoria")
        String nonMetalSymbol,

        @NotBlank(message = "El nombre del anión es obligatorio")
        String anionName,

        @NotNull(message = "La carga del anión es obligatoria")
        @Min(value = 1, message = "La carga del anión debe ser mayor o igual a 1")
        Integer anionCharge
) {
}