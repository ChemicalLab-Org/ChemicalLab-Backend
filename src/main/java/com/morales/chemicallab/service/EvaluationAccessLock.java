package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** All T05 decisions and mutations serialize on the evaluation, then the attempt. */
@Component
@RequiredArgsConstructor
public class EvaluationAccessLock {
    private final EntityManager entities;

    public void evaluation(Evaluation evaluation) {
        entities.refresh(evaluation, LockModeType.PESSIMISTIC_WRITE);
    }

    public void assignment(EvaluationAssignment assignment) {
        evaluation(assignment.getEvaluation());
        entities.refresh(assignment);
    }

    public void attempt(EvaluationAttempt attempt) {
        evaluation(attempt.getEvaluation());
        entities.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);
        if (attempt.getAssignment() != null) entities.refresh(attempt.getAssignment());
    }
}
