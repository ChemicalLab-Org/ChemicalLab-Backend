package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;

/** Academic wall times are America/Lima, including existing timestamp-without-zone values. */
@Component
public class EvaluationAccessPolicy {
    public static final ZoneId ACADEMIC_ZONE = ZoneId.of("America/Lima");
    private final Clock clock;

    public EvaluationAccessPolicy(@Qualifier("evaluationClock") Clock clock) { this.clock = clock; }
    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ACADEMIC_ZONE); }

    public enum Window { SCHEDULED, AVAILABLE, EXPIRED, UNAVAILABLE }

    public void validateInterval(LocalDateTime start, LocalDateTime due) {
        if (start != null && due != null && !due.isAfter(start))
            throw new IllegalArgumentException("La fecha límite debe ser posterior al inicio.");
    }

    public Window window(EvaluationAssignment assignment) {
        if (assignment == null || !Boolean.TRUE.equals(assignment.getActive())
                || !published(assignment.getEvaluation())) return Window.UNAVAILABLE;
        var start = assignment.getStartAt();
        var due = assignment.getDueAt();
        // Invalid legacy intervals fail closed without rewriting persisted dates.
        if (start != null && due != null && !due.isAfter(start)) return Window.UNAVAILABLE;
        var now = now();
        if (start != null && now.isBefore(start)) return Window.SCHEDULED;
        if (due != null && !now.isBefore(due)) return Window.EXPIRED;
        return Window.AVAILABLE;
    }

    public boolean published(Evaluation evaluation) {
        return Boolean.TRUE.equals(evaluation.getActive()) && evaluation.getStatus() == EvaluationStatus.PUBLISHED;
    }

    public boolean beforeStart(EvaluationAssignment assignment) {
        return assignment != null && assignment.getStartAt() != null && now().isBefore(assignment.getStartAt());
    }

    public void requireStart(EvaluationAssignment assignment) {
        switch (window(assignment)) {
            case SCHEDULED -> throw new IllegalArgumentException("La evaluación está programada; aún no ha comenzado.");
            case EXPIRED -> throw new IllegalArgumentException("El plazo de la evaluación ya venció.");
            case UNAVAILABLE -> throw new IllegalArgumentException("La evaluación ya no está disponible.");
            default -> { }
        }
    }

    /** Exclusive deadline; no grace that would allow answers after review unlock. */
    public LocalDateTime answerDeadline(EvaluationAttempt attempt) {
        LocalDateTime deadline = attempt.getAssignment() == null ? null : attempt.getAssignment().getDueAt();
        Integer minutes = attempt.getEvaluation().getTimeLimitMinutes();
        if (minutes != null && minutes > 0 && attempt.getStartedAt() != null) {
            var duration = attempt.getStartedAt().plusMinutes(minutes);
            if (deadline == null || duration.isBefore(deadline)) deadline = duration;
        }
        return deadline;
    }

    public boolean canWrite(EvaluationAttempt attempt) {
        var assignment = attempt.getAssignment();
        var student = attempt.getStudent();
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS || !Boolean.TRUE.equals(attempt.getActive())
                || assignment == null || window(assignment) != Window.AVAILABLE
                || !assignment.getGrade().equals(student.getGrade()) || !assignment.getSection().equals(student.getSection())
                || !Boolean.TRUE.equals(student.getUser().getActive())) return false;
        var deadline = answerDeadline(attempt);
        return deadline == null || now().isBefore(deadline);
    }

    public boolean exposeCorrection(EvaluationAttempt attempt, boolean reviewAvailable) {
        return reviewAvailable && !beforeStart(attempt.getAssignment()) && attempt.getStatus() == AttemptStatus.GRADED
                && Boolean.TRUE.equals(attempt.getGradeClosed());
    }

    /** T05 closes the whole evaluation, including future, empty and undated assignments. */
    public boolean reviewAvailable(Evaluation evaluation, List<EvaluationAssignment> assignments) {
        var active = assignments.stream().filter(a -> Boolean.TRUE.equals(a.getActive())).toList();
        return !published(evaluation) || (!assignments.isEmpty() && active.isEmpty())
                || (!active.isEmpty() && active.stream().allMatch(a -> a.getDueAt() != null && !now().isBefore(a.getDueAt())));
    }
}
