package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.EvaluationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista de supervisión de una evaluación para el administrador. Es de solo lectura e
 * incluye metadatos académicos (título, tema, estado, docente autor, número de
 * preguntas, asignaciones, intentos enviados y fechas).
 *
 * <p>De forma deliberada <strong>no</strong> incluye las preguntas ni sus
 * alternativas, por lo que nunca expone la clave de respuestas. El detalle de
 * preguntas, sin la alternativa correcta, está disponible en el endpoint específico
 * de detalle administrativo.</p>
 */
public record SupervisionEvaluationResponse(
        Long id,
        String title,
        String topic,
        EvaluationStatus status,
        String createdByTeacher,
        long questionCount,
        long assignmentCount,
        long submittedAttempts,
        List<SupervisionSectionRef> sections,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
