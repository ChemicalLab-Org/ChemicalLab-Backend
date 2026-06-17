package com.morales.chemicallab.entity;

/**
 * Estado del ciclo de vida de un contenido conceptual.
 * - DRAFT: borrador, solo visible para su docente.
 * - PUBLISHED: publicado, visible para los estudiantes de las secciones asignadas.
 * - ARCHIVED: archivado, deja de estar disponible y no admite nuevas asignaciones.
 */
public enum ConceptStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
