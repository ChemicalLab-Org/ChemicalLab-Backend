package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.ConceptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista de supervisión de un contenido conceptual para el administrador. Es de solo
 * lectura: incluye los metadatos académicos (título, categoría, estado, docente
 * autor, secciones asignadas y fechas) pero <strong>no</strong> el cuerpo completo
 * del contenido, manteniendo la respuesta acotada a lo necesario para supervisar.
 */
public record SupervisionConceptResponse(
        Long id,
        String title,
        String category,
        ConceptStatus status,
        String createdByTeacher,
        long assignmentCount,
        long materialCount,
        boolean hasAttachment,
        List<SupervisionSectionRef> sections,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
