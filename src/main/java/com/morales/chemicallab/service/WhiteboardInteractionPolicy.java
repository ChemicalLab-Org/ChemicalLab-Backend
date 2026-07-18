package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.WhiteboardInteractionOverride;

/**
 * Regla única del permiso efectivo de interacción de un alumno en una sesión de pizarra.
 * Combina el permiso global de la sesión con el permiso individual del participante.
 *
 * <ul>
 *   <li>{@code ALLOWED}: puede interactuar aunque el global esté desactivado.</li>
 *   <li>{@code BLOCKED}: no puede interactuar aunque el global esté activado.</li>
 *   <li>{@code FOLLOW_GLOBAL}: sigue el permiso global de la sesión.</li>
 * </ul>
 *
 * <p>Es la fuente de verdad del backend, compartida por la lógica REST y por la validación
 * de eventos de dibujo entrantes por WebSocket.</p>
 */
final class WhiteboardInteractionPolicy {

    private WhiteboardInteractionPolicy() {
    }

    static boolean effective(boolean globalEnabled, WhiteboardInteractionOverride override) {
        WhiteboardInteractionOverride resolved =
                override == null ? WhiteboardInteractionOverride.FOLLOW_GLOBAL : override;
        return switch (resolved) {
            case ALLOWED -> true;
            case BLOCKED -> false;
            case FOLLOW_GLOBAL -> globalEnabled;
        };
    }
}
