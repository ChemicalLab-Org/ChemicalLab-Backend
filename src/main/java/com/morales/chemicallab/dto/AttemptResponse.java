package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista de un intento de evaluación del estudiante, con sus respuestas. El puntaje
 * ({@code score}/{@code maxScore}) queda disponible una vez enviado el intento.
 *
 * <p>{@code questionOrder} es el orden de preguntas fijado para este intento (IDs); el
 * frontend debe presentar las preguntas en ese orden. {@code currentQuestionIndex}
 * indica, en el modo una por una, la pregunta actual: las anteriores quedan bloqueadas.</p>
 *
 * <p>{@code allowChemicalCalculator} y {@code allowPeriodicTable} reflejan la
 * configuración de la evaluación: indican qué módulos de apoyo (Formación de compuestos
 * y Tabla periódica) puede usar el estudiante durante este intento. El frontend los usa
 * para mostrar/ocultar esas opciones y para controlar la navegación durante el examen.</p>
 */
public record AttemptResponse(
        Long id,
        Long evaluationId,
        Long assignmentId,
        AttemptStatus status,
        Integer attemptNumber,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        Integer score,
        Integer maxScore,
        List<Long> questionOrder,
        Integer currentQuestionIndex,
        boolean allowChemicalCalculator,
        boolean allowPeriodicTable,
        List<EvaluationAnswerResponse> answers
) {}
