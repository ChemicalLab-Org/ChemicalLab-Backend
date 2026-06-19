package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.util.List;

/**
 * Pregunta vista por el docente, con sus alternativas (incluyendo cuál es correcta).
 */
public record QuestionResponse(
        Long id,
        String questionText,
        QuestionType questionType,
        Integer points,
        Integer orderIndex,
        String explanation,
        List<OptionResponse> options
) {}
