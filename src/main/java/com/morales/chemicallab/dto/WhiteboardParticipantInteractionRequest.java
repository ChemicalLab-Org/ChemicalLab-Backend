package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardInteractionOverride;
import jakarta.validation.constraints.NotNull;

/**
 * Actualización del permiso individual de interacción de un alumno en una sesión.
 * {@code ALLOWED}/{@code BLOCKED} fuerzan el permiso; {@code FOLLOW_GLOBAL} delega en el
 * permiso global de la sesión.
 */
public record WhiteboardParticipantInteractionRequest(
        @NotNull(message = "El permiso de interacción es obligatorio.")
        WhiteboardInteractionOverride interactionOverride
) {
}
