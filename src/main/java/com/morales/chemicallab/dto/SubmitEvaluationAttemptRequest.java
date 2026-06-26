package com.morales.chemicallab.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Envío de un intento. Las respuestas son opcionales: el estudiante puede haberlas
 * guardado de forma incremental con anterioridad. Si se incluyen, se persisten
 * (validándolas) antes de calcular el puntaje.
 */
public record SubmitEvaluationAttemptRequest(

        @Valid
        List<SubmitEvaluationAnswerRequest> answers

) {}
