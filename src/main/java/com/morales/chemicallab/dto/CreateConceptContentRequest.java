package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.ConceptCategory;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreateConceptContentRequest(

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar 150 caracteres")
        String title,

        @NotNull(message = "La categoría es obligatoria")
        ConceptCategory category,

        @Size(max = 500, message = "El resumen no puede superar 500 caracteres")
        String summary,

        @NotBlank(message = "La explicación es obligatoria")
        String explanation,

        List<String> formationSteps,

        List<String> keyPoints,

        List<String> examples

) {}
