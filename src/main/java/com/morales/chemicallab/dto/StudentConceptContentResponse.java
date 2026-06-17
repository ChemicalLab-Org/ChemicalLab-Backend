package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.ConceptCategory;

import java.util.List;

/**
 * Vista reducida de un contenido conceptual para el estudiante. No expone estado,
 * autor ni asignaciones: solo el material publicado que le corresponde consultar.
 */
public record StudentConceptContentResponse(
        Long id,
        String title,
        ConceptCategory category,
        String summary,
        String explanation,
        List<String> formationSteps,
        List<String> keyPoints,
        List<String> examples
) {}
