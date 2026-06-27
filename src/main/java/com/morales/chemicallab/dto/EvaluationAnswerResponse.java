package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.time.LocalDateTime;

/**
 * Respuesta registrada dentro de un intento. Para alternativa única se usa
 * {@code selectedOptionId}; para preguntas abiertas, {@code answerText} (el propio texto
 * del estudiante, que sí puede ver para repoblar el formulario). Los campos
 * {@code correct} y {@code pointsAwarded} solo reflejan la calificación una vez enviado
 * y, en preguntas abiertas, revisado el intento.
 */
public record EvaluationAnswerResponse(
        Long id,
        Long questionId,
        QuestionType questionType,
        Long selectedOptionId,
        String answerText,
        Boolean correct,
        Integer pointsAwarded,
        LocalDateTime answeredAt
) {}
