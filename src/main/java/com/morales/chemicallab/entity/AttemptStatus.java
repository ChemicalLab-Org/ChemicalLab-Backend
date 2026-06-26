package com.morales.chemicallab.entity;

/**
 * Estado de un intento de evaluación de un estudiante.
 * - IN_PROGRESS: intento iniciado, el estudiante aún puede guardar respuestas.
 * - SUBMITTED: intento enviado; ya no admite cambios. Estado de transición/heredado:
 *   los intentos de alternativa única pasan directo a GRADED al enviarse.
 * - GRADED: intento calificado de forma definitiva (tiene score y maxScore). Es el
 *   estado final de un intento enviado en evaluaciones de alternativa única.
 */
public enum AttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    GRADED
}
