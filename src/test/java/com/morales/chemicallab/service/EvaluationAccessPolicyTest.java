package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationAccessPolicyTest {
    final LocalDateTime start = LocalDateTime.of(2030, 6, 3, 10, 0);
    EvaluationAccessPolicy at(LocalDateTime time) {
        // A clock with a foreign zone must still produce Lima wall times.
        return new EvaluationAccessPolicy(Clock.fixed(time.atZone(EvaluationAccessPolicy.ACADEMIC_ZONE).toInstant(), ZoneId.of("Asia/Tokyo")));
    }
    EvaluationAssignment slot() {
        return EvaluationAssignment.builder().grade("3").section("A").startAt(start).dueAt(start.plusMinutes(20))
                .evaluation(Evaluation.builder().status(EvaluationStatus.PUBLISHED).timeLimitMinutes(10).build()).build();
    }
    @Test void startInclusiveDueExclusiveWithNanosecondBoundaries() {
        var slot = slot();
        assertThat(at(start.minusNanos(1)).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.SCHEDULED);
        assertThat(at(start).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.AVAILABLE);
        assertThat(at(start.plusNanos(1)).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.AVAILABLE);
        assertThat(at(slot.getDueAt().minusNanos(1)).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.AVAILABLE);
        assertThat(at(slot.getDueAt()).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.EXPIRED);
        assertThat(at(slot.getDueAt().plusNanos(1)).window(slot)).isEqualTo(EvaluationAccessPolicy.Window.EXPIRED);
        assertThat(at(start).now()).isEqualTo(start);
    }
    @Test void optionalDatesAndInvalidLegacyRangesFailClosed() {
        var policy = at(start); var slot = slot();
        slot.setStartAt(null); assertThat(policy.window(slot)).isEqualTo(EvaluationAccessPolicy.Window.AVAILABLE);
        slot.setDueAt(null); assertThat(policy.window(slot)).isEqualTo(EvaluationAccessPolicy.Window.AVAILABLE);
        slot.setStartAt(start.plusDays(1)); assertThat(policy.window(slot)).isEqualTo(EvaluationAccessPolicy.Window.SCHEDULED);
        assertThatCode(() -> policy.validateInterval(null, start)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateInterval(start, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateInterval(start, start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateInterval(start, start.minusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        slot.setDueAt(start); assertThat(policy.window(slot)).isEqualTo(EvaluationAccessPolicy.Window.UNAVAILABLE);
    }
    @Test void attemptDurationAndAssignmentDeadlineUseMinimumWithoutGrace() {
        var slot = slot();
        var pupil = StudentProfile.builder().grade("3").section("A").user(UserAccount.builder().active(true).build()).build();
        var attempt = EvaluationAttempt.builder().evaluation(slot.getEvaluation()).assignment(slot).student(pupil).startedAt(start).build();
        assertThat(at(start.plusMinutes(10).minusNanos(1)).canWrite(attempt)).isTrue();
        assertThat(at(start.plusMinutes(10)).canWrite(attempt)).isFalse();
        assertThat(at(start.plusMinutes(10).plusSeconds(1)).canWrite(attempt)).isFalse();
        slot.setDueAt(start.plusMinutes(5));
        assertThat(at(start).answerDeadline(attempt)).isEqualTo(start.plusMinutes(5));
        assertThat(at(start.plusMinutes(5)).canWrite(attempt)).isFalse();
        slot.setActive(false); assertThat(at(start).canWrite(attempt)).isFalse();
        slot.setActive(true); slot.getEvaluation().setStatus(EvaluationStatus.ARCHIVED);
        assertThat(at(start).canWrite(attempt)).isFalse();
    }
    @Test void exposureRequiresGroupClosureGradeClosureAndFinalGrading() {
        var attempt = EvaluationAttempt.builder().assignment(slot()).status(AttemptStatus.GRADED).gradeClosed(true).build();
        var policy = at(start);
        assertThat(policy.exposeCorrection(attempt, false)).isFalse();
        assertThat(policy.exposeCorrection(attempt, true)).isTrue();
        attempt.setGradeClosed(false); assertThat(policy.exposeCorrection(attempt, true)).isFalse();
        attempt.setGradeClosed(true); attempt.setStatus(AttemptStatus.PENDING_MANUAL_REVIEW);
        assertThat(policy.exposeCorrection(attempt, true)).isFalse();
        attempt.setStatus(AttemptStatus.GRADED);
        assertThat(at(start.minusNanos(1)).exposeCorrection(attempt, true)).isFalse();
    }
}
