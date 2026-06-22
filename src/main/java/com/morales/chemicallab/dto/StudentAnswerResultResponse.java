package com.morales.chemicallab.dto;

/**
 * Detalle de una pregunta dentro del resultado de un intento, para el estudiante.
 * Siempre muestra su respuesta, si fue correcta y los puntos obtenidos. La
 * alternativa correcta ({@code correctOptionText}) y la explicación solo se rellenan
 * cuando el estudiante tiene permitido ver la retroalimentación detallada; en caso
 * contrario van en null para no revelar las respuestas mientras quedan intentos.
 */
public record StudentAnswerResultResponse(
        Long questionId,
        String questionText,
        String selectedOptionText,
        Boolean correct,
        Integer points,
        Integer pointsAwarded,
        String correctOptionText,
        String explanation
) {}
