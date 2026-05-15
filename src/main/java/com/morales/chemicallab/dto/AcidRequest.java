package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AcidRequest(
        @NotBlank(message = "La fórmula del anión es obligatoria")
        String anionFormula,

        @NotBlank(message = "El nombre del anión es obligatorio")
        String anionName,

        @NotNull(message = "La carga del anión es obligatoria")
        @Min(value = 1, message = "La carga debe ser mayor o igual a 1")
        Integer anionCharge,

        @NotBlank(message = "El nombre del ácido es obligatorio")
        String acidName
) {
}