package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record UpdateEvaluationRequest(

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar 150 caracteres")
        String title,

        @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
        String description,

        @Size(max = 2000, message = "Las instrucciones no pueden superar 2000 caracteres")
        String instructions,

        @Size(max = 120, message = "El tema no puede superar 120 caracteres")
        String topic,

        @NotNull(message = "El número máximo de intentos es obligatorio")
        @Min(value = 1, message = "El número máximo de intentos debe ser al menos 1")
        Integer maxAttempts,

        @Positive(message = "El límite de tiempo debe ser positivo")
        Integer timeLimitMinutes

) {}
