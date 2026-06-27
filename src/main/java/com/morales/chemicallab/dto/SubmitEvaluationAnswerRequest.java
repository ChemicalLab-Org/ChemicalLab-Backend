package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Respuesta del estudiante a una pregunta. Se usa tanto al guardar respuestas de forma
 * incremental como dentro del envío del intento. Para alternativa única se envía
 * {@code selectedOptionId}; para preguntas abiertas se envía {@code answerText}. El
 * servicio toma solo el campo que corresponde según el tipo de la pregunta.
 */
public record SubmitEvaluationAnswerRequest(

        @NotNull(message = "La pregunta es obligatoria")
        Long questionId,

        // Alternativa elegida. Puede ser null si el estudiante deja la pregunta en blanco.
        Long selectedOptionId,

        // Texto de respuesta para preguntas abiertas. Puede ser null/vacío mientras el
        // intento sigue en progreso; la obligatoriedad se valida al enviar.
        @Size(max = 3000, message = "La respuesta no puede superar 3000 caracteres")
        String answerText

) {}
