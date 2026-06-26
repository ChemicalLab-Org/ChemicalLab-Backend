package com.morales.chemicallab.entity;

/**
 * Estado del ciclo de vida de una evaluación.
 * - DRAFT: borrador, solo visible para su docente; admite edición de preguntas.
 * - PUBLISHED: publicada, visible para los estudiantes de las secciones asignadas.
 * - ARCHIVED: archivada, deja de estar disponible y no admite nuevas asignaciones.
 */
public enum EvaluationStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
