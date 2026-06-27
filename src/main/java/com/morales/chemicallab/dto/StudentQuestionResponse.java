package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.QuestionType;

import java.util.List;

/**
 * Pregunta vista por el estudiante. En alternativa única incluye sus alternativas pero
 * sin revelar cuál es la correcta; en preguntas abiertas la lista de alternativas va
 * vacía y el estudiante responde con texto. Nunca se incluye {@code expectedAnswer} ni
 * el criterio de corrección. {@code required} indica si debe responderla para enviar.
 */
public record StudentQuestionResponse(
        Long id,
        String questionText,
        QuestionType questionType,
        Integer points,
        Integer orderIndex,
        Boolean required,
        List<StudentOptionResponse> options
) {}
