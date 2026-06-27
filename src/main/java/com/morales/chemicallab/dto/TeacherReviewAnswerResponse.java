package com.morales.chemicallab.dto;

/**
 * Respuesta abierta a revisar por el docente. Incluye el texto escrito por el estudiante,
 * el puntaje máximo de la pregunta, el criterio de corrección ({@code expectedAnswer},
 * visible solo para el docente) y, si ya fue revisada, el puntaje asignado y la
 * retroalimentación. {@code answerId} es null si el estudiante no dejó respuesta (la
 * pregunta igual debe calificarse, normalmente con cero).
 */
public record TeacherReviewAnswerResponse(
        Long answerId,
        Long questionId,
        String questionText,
        Integer maxPoints,
        String expectedAnswer,
        String answerText,
        Boolean reviewed,
        Integer awardedScore,
        String teacherFeedback
) {}
