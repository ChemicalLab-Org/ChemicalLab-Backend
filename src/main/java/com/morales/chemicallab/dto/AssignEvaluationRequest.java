package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

public record AssignEvaluationRequest(

        @NotBlank(message = "El grado es obligatorio")
        @Size(max = 20, message = "El grado no puede superar 20 caracteres")
        String grade,

        @NotBlank(message = "La sección es obligatoria")
        @Size(max = 20, message = "La sección no puede superar 20 caracteres")
        String section,

        // Ventana de disponibilidad opcional.
        LocalDateTime startAt,

        LocalDateTime dueAt

) {}
