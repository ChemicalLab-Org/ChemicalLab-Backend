package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionDisplayMode;

import java.util.List;

/**
 * Vista de detalle de una evaluación asignada para el estudiante: incluye las
 * preguntas con sus alternativas, sin revelar cuáles son correctas.
 */
public record StudentEvaluationDetailResponse(
        Long id,
        String title,
        String description,
        String instructions,
        String topic,
        Integer timeLimitMinutes,
        Boolean allowChemicalCalculator,
        Boolean allowPeriodicTable,
        Boolean trackTabExit,
        QuestionDisplayMode questionDisplayMode,
        Boolean randomizeQuestions,
        List<StudentQuestionResponse> questions,
        Long assignmentId,
        java.time.LocalDateTime startAt,
        java.time.LocalDateTime dueAt,
        String accessState,
        boolean questionsVisible,
        java.time.LocalDateTime serverNow
) {}
