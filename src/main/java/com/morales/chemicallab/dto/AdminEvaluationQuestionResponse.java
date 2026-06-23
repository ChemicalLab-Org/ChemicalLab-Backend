package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.util.List;

/**
 * Pregunta vista por el administrador en la supervisión de una evaluación.
 *
 * <p>Permite revisar el enunciado y las alternativas para fines de control
 * institucional, pero <strong>no</strong> incluye la alternativa correcta ni la
 * explicación, de modo que no se expone la clave de respuestas.</p>
 */
public record AdminEvaluationQuestionResponse(
        Long id,
        String questionText,
        QuestionType questionType,
        Integer points,
        Integer orderIndex,
        List<AdminEvaluationOptionResponse> options
) {}
