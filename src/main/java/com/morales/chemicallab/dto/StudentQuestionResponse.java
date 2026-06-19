package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.util.List;

/**
 * Pregunta vista por el estudiante: incluye sus alternativas pero sin revelar cuál
 * es la correcta.
 */
public record StudentQuestionResponse(
        Long id,
        String questionText,
        QuestionType questionType,
        Integer points,
        Integer orderIndex,
        List<StudentOptionResponse> options
) {}
