package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.util.List;

/**
 * Pregunta vista por el docente. Para alternativa única incluye sus alternativas
 * (con cuál es la correcta); para preguntas abiertas incluye {@code expectedAnswer}
 * (criterio de corrección, solo docente) y la lista de alternativas va vacía.
 */
public record QuestionResponse(
        Long id,
        String questionText,
        QuestionType questionType,
        Integer points,
        Integer orderIndex,
        String explanation,
        String expectedAnswer,
        Boolean required,
        List<OptionResponse> options
) {}
