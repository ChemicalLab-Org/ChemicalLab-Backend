package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OxisaltRequest(
        @NotBlank(message = "El símbolo del metal es obligatorio")
        String metalSymbol,

        @NotBlank(message = "El nombre del metal es obligatorio")
        String metalName,

        @NotNull(message = "La valencia del metal es obligatoria")
        @Min(value = 1, message = "La valencia del metal debe ser mayor o igual a 1")
        Integer metalValence,

        @NotBlank(message = "La fórmula del grupo químico es obligatoria")
        String groupFormula,

        @NotBlank(message = "El nombre del grupo químico es obligatorio")
        String groupName,

        @NotNull(message = "La carga del grupo químico es obligatoria")
        @Min(value = 1, message = "La carga del grupo químico debe ser mayor o igual a 1")
        Integer groupCharge
) {
}