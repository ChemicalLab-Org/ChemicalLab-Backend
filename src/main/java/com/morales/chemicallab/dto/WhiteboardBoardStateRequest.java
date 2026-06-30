package com.morales.chemicallab.dto;

/**
 * Cuerpo para guardar el estado actual del lienzo de una sesión en vivo. El frontend docente lo
 * envía de forma debounced con una instantánea serializada (trazos + textos). El backend acota su
 * tamaño y no interpreta su contenido.
 */
public record WhiteboardBoardStateRequest(
        String stateJson
) {
}
