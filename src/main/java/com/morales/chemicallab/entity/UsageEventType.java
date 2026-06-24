package com.morales.chemicallab.entity;

/**
 * Tipo de interacción registrada como métrica de uso.
 *
 * <p>A diferencia de {@link LogEventType} (que describe acciones críticas de seguridad y
 * trazabilidad), estos eventos describen la interacción educativa del usuario con los
 * módulos: acceder a una pantalla, abrir un recurso, usar la tabla periódica, etc.</p>
 */
public enum UsageEventType {
    /** El usuario entró a un módulo principal. */
    MODULE_ACCESS,
    /** Clic relevante dentro de un módulo (no cada botón de navegación). */
    IMPORTANT_CLICK,
    /** Se abrió o visualizó un contenido conceptual. */
    CONTENT_VIEW,
    /** El estudiante abrió el detalle de una evaluación. */
    EVALUATION_OPENED,
    /** El estudiante inició un intento de evaluación. */
    EVALUATION_STARTED,
    /** Se usó la formación de compuestos (intento o validación). */
    COMPOUND_FORMATION_USED,
    /** Se abrió el detalle de un elemento de la tabla periódica. */
    PERIODIC_ELEMENT_VIEWED,
    /** Se visualizaron resultados de evaluación. */
    RESULTS_VIEWED
}
