package com.morales.chemicallab.entity;

/**
 * Estado de un intento de evaluación de un estudiante.
 * - IN_PROGRESS: intento iniciado, el estudiante aún puede guardar respuestas.
 * - SUBMITTED: intento enviado; ya no admite cambios.
 * - GRADED: intento calificado de forma definitiva. Se reserva para el módulo de
 *   resultados/calificaciones, que se implementará en una sesión posterior.
 */
public enum AttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    GRADED
}
