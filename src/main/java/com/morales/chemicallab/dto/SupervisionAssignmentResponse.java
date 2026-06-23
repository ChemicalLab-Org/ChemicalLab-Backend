package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Asignación académica vista por el administrador en la supervisión. Unifica las
 * asignaciones de contenidos y de evaluaciones en una sola fila de solo lectura.
 *
 * @param type             tipo de recurso asignado: {@code "CONTENIDO"} o {@code "EVALUACION"}.
 * @param resourceId       id del contenido o evaluación asignado.
 * @param title            título del recurso asignado.
 * @param createdByTeacher docente responsable del recurso.
 * @param grade            grado destino.
 * @param section          sección destino.
 * @param active           si la asignación está activa.
 * @param assignedAt       fecha de la asignación.
 */
public record SupervisionAssignmentResponse(
        String type,
        Long resourceId,
        String title,
        String createdByTeacher,
        String grade,
        String section,
        boolean active,
        LocalDateTime assignedAt
) {}
