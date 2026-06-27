package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

/**
 * Detalle de una pregunta dentro del resultado de un intento, para el docente.
 * Para alternativa única el docente ve la alternativa elegida, la correcta y la
 * explicación. Para preguntas abiertas ve el texto escrito por el estudiante
 * ({@code answerText}), el puntaje asignado, su retroalimentación
 * ({@code teacherFeedback}) y si ya fue revisada ({@code reviewed}). Si el estudiante
 * no respondió, los campos de respuesta van en null.
 */
public record TeacherAnswerResultResponse(
        Long questionId,
        String questionText,
        QuestionType questionType,
        Long selectedOptionId,
        String selectedOptionText,
        Long correctOptionId,
        String correctOptionText,
        String answerText,
        Boolean correct,
        Integer points,
        Integer pointsAwarded,
        Boolean reviewed,
        String teacherFeedback,
        String explanation
) {}
