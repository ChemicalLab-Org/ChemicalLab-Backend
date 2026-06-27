package com.morales.chemicallab.entity;

/**
 * Estado de un intento de evaluación de un estudiante.
 * - IN_PROGRESS: intento iniciado, el estudiante aún puede guardar respuestas.
 * - SUBMITTED: intento enviado; ya no admite cambios. Estado de transición/heredado:
 *   los intentos solo de alternativa única pasan directo a GRADED al enviarse.
 * - PENDING_MANUAL_REVIEW: intento enviado que contiene preguntas abiertas todavía sin
 *   calificar. La parte de alternativa única ya está corregida (puntaje parcial), pero
 *   la nota final no es definitiva hasta que el docente revise todas las respuestas
 *   abiertas. No debe confundirse con un intento abandonado.
 * - GRADED: intento calificado de forma definitiva (tiene score y maxScore). Es el
 *   estado final de un intento enviado: directo para evaluaciones solo de alternativa
 *   única, o tras completar la revisión manual en evaluaciones con preguntas abiertas.
 */
public enum AttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    PENDING_MANUAL_REVIEW,
    GRADED
}
