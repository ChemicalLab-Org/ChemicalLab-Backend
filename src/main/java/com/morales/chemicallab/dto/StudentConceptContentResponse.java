package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Vista reducida de un contenido conceptual para el estudiante. No expone estado,
 * autor ni asignaciones: solo el material publicado que le corresponde consultar. La
 * categoría es texto libre.
 */
public record StudentConceptContentResponse(
        Long id,
        String title,
        String category,
        String summary,
        String explanation,
        List<String> formationSteps,
        List<String> keyPoints,
        List<String> examples,
        String suggestedActivity,
        List<ConceptMaterialResponse> materials
) {}
