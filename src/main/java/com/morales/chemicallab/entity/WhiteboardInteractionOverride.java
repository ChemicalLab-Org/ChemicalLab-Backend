package com.morales.chemicallab.entity;

/**
 * Permiso individual de interacción de un {@link WhiteboardParticipant}, que se combina con
 * el permiso global de la sesión ({@link WhiteboardSession#getInteractionEnabled()}) para
 * obtener el permiso efectivo de dibujo del estudiante.
 *
 * <p>Se usa un enum en lugar de un {@code Boolean} anulable para que el estado sea explícito
 * y no quede una lógica que impida bloquear a un alumno cuando la interacción global está
 * activada.</p>
 *
 * <ul>
 *   <li>{@code FOLLOW_GLOBAL}: el alumno sigue el permiso global de la sesión (valor por
 *       defecto al unirse).</li>
 *   <li>{@code ALLOWED}: el alumno puede interactuar aunque el global esté desactivado.</li>
 *   <li>{@code BLOCKED}: el alumno no puede interactuar aunque el global esté activado.</li>
 * </ul>
 */
public enum WhiteboardInteractionOverride {
    FOLLOW_GLOBAL,
    ALLOWED,
    BLOCKED
}
