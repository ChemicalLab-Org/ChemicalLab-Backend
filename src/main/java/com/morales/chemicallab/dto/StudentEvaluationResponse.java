package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.QuestionDisplayMode;

import java.time.LocalDateTime;

/**
 * Vista resumida de una evaluación disponible para el estudiante. Incorpora datos de
 * la asignación (ventana de fechas) y del progreso del estudiante (intentos usados y
 * estado del último intento). No expone el estado interno ni el autor.
 */
public record StudentEvaluationResponse(
        Long id,
        String title,
        String description,
        String instructions,
        String topic,
        Integer timeLimitMinutes,
        Integer maxAttempts,
        Boolean allowChemicalCalculator,
        Boolean trackTabExit,
        QuestionDisplayMode questionDisplayMode,
        Boolean randomizeQuestions,
        long questionCount,
        Long assignmentId,
        LocalDateTime startAt,
        LocalDateTime dueAt,
        AttemptStatus attemptStatus,
        int attemptsUsed
) {}
