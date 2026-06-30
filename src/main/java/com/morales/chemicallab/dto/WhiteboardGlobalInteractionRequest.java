package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Activación/desactivación del permiso global de interacción de una sesión.
 */
public record WhiteboardGlobalInteractionRequest(
        @NotNull(message = "El estado de interacción es obligatorio.")
        Boolean interactionEnabled
) {
}
