package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ElementCompoundRequest(
        @NotBlank(message = "El símbolo del elemento es obligatorio")
        String elementSymbol,

        @NotBlank(message = "El nombre del elemento es obligatorio")
        String elementName,

        @NotNull(message = "La valencia es obligatoria")
        @Min(value = 1, message = "La valencia debe ser mayor o igual a 1")
        Integer valence
) {
}