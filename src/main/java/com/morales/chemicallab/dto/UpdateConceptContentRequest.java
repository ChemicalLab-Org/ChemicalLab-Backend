package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

import java.util.List;

public record UpdateConceptContentRequest(

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar 150 caracteres")
        String title,

        @NotBlank(message = "La categoría es obligatoria")
        @Size(max = 100, message = "La categoría no puede superar 100 caracteres")
        String category,

        @Size(max = 500, message = "El resumen no puede superar 500 caracteres")
        String summary,

        @NotBlank(message = "La explicación es obligatoria")
        String explanation,

        List<String> formationSteps,

        List<String> keyPoints,

        List<String> examples,

        @Size(max = 2000, message = "La actividad sugerida no puede superar 2000 caracteres")
        String suggestedActivity

) {}
