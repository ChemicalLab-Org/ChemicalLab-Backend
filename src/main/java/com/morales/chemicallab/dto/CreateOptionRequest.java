package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record CreateOptionRequest(

        @NotBlank(message = "El texto de la alternativa es obligatorio")
        @Size(max = 500, message = "La alternativa no puede superar 500 caracteres")
        String optionText,

        @NotNull(message = "Debe indicarse si la alternativa es correcta")
        Boolean correct,

        Integer orderIndex

) {}
