package com.morales.chemicallab.dto;

/**
 * Tipo de evento de dibujo recibido y difundido por WebSocket. No se persiste: es solo el
 * transporte en vivo.
 */
public enum WhiteboardDrawEventType {
    /** Trazo a mano alzada. */
    DRAW,
    /** Borrado parcial con el borrador. */
    ERASE,
    /** Limpiar toda la pizarra (reservado al docente). */
    CLEAR,
    /** Crear o actualizar un objeto de texto (reservado al docente). */
    TEXT,
    /** Eliminar un objeto de texto por su identificador (reservado al docente). */
    TEXT_DELETE,
    /** Crear, actualizar o mover una forma estructurada. */
    SHAPE,
    /** Eliminar una forma estructurada por su identificador. */
    SHAPE_DELETE,
    /** Eliminar un trazo (DRAW/ERASE) por su identificador estable (deshacer/rehacer). */
    STROKE_DELETE
}
