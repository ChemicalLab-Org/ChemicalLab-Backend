package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Estado actual del lienzo de una sesión en vivo. {@link #stateJson} es {@code null} si todavía no
 * se ha guardado ningún estado (sesión recién creada o sin dibujo). Permite que un estudiante que
 * entra tarde o recarga reconstruya lo ya dibujado antes de seguir recibiendo eventos en vivo.
 */
public record WhiteboardBoardStateResponse(
        Long sessionId,
        WhiteboardSessionStatus status,
        String stateJson,
        LocalDateTime updatedAt
) {
}
