package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

/**
 * Detalle de una pregunta dentro del resultado de un intento, para el estudiante.
 * Siempre muestra su respuesta (alternativa elegida o su propio {@code answerText}) y,
 * en preguntas abiertas, los puntos obtenidos y la retroalimentación del docente solo
 * cuando esa respuesta ya fue revisada ({@code reviewed}); antes de la revisión no se
 * muestra puntaje ni retroalimentación. La alternativa correcta y la explicación solo
 * se rellenan cuando el estudiante tiene permitido ver la retroalimentación detallada;
 * en caso contrario van en null para no revelar las respuestas mientras quedan intentos.
 */
public record StudentAnswerResultResponse(
        Long questionId,
        String questionText,
        QuestionType questionType,
        String selectedOptionText,
        String answerText,
        Boolean correct,
        Integer points,
        Integer pointsAwarded,
        Boolean reviewed,
        String teacherFeedback,
        String correctOptionText,
        String explanation
) {}
