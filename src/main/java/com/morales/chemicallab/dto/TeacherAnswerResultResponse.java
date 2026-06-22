package com.morales.chemicallab.dto;

/**
 * Detalle de una pregunta dentro del resultado de un intento, para el docente.
 * El docente sí ve la alternativa correcta y la explicación. Si el estudiante no
 * respondió la pregunta, {@code selectedOptionId} y {@code selectedOptionText} van
 * en null y {@code correct} es false.
 */
public record TeacherAnswerResultResponse(
        Long questionId,
        String questionText,
        Long selectedOptionId,
        String selectedOptionText,
        Long correctOptionId,
        String correctOptionText,
        Boolean correct,
        Integer points,
        Integer pointsAwarded,
        String explanation
) {}
