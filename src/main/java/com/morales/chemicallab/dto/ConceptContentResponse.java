package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.ConceptCategory;
import com.morales.chemicallab.entity.ConceptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista completa de un contenido conceptual para el docente o el administrador.
 * Incluye el estado, el docente autor y las asignaciones a grado/sección.
 */
public record ConceptContentResponse(
        Long id,
        String title,
        ConceptCategory category,
        String summary,
        String explanation,
        List<String> formationSteps,
        List<String> keyPoints,
        List<String> examples,
        ConceptStatus status,
        Boolean active,
        Long createdByTeacherId,
        String createdByTeacherName,
        List<ConceptAssignmentResponse> assignments,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
