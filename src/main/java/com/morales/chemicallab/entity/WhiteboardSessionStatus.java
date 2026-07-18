package com.morales.chemicallab.entity;

/**
 * Estado del ciclo de vida de una {@link WhiteboardSession}. Se mantiene la convención del
 * proyecto (inglés, mayúsculas), como en {@link EvaluationStatus}.
 *
 * <ul>
 *   <li>{@code ACTIVE}: sesión en vivo; admite dibujo si hay permiso efectivo.</li>
 *   <li>{@code PAUSED}: sesión visible pero detenida; no acepta eventos de dibujo.</li>
 *   <li>{@code CLOSED}: estado terminal. No acepta dibujo, no permite reapertura ni
 *       edición y conserva la captura final en el historial.</li>
 * </ul>
 */
public enum WhiteboardSessionStatus {
    ACTIVE,
    PAUSED,
    CLOSED
}
