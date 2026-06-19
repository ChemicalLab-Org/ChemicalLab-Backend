package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Respuesta del estudiante a una pregunta de alternativa única. Se usa tanto al
 * guardar respuestas de forma incremental como dentro del envío del intento.
 */
public record SubmitEvaluationAnswerRequest(

        @NotNull(message = "La pregunta es obligatoria")
        Long questionId,

        // Alternativa elegida. Puede ser null si el estudiante deja la pregunta en blanco.
        Long selectedOptionId

) {}
