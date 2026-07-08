package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.EvaluationAnswer;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationAnswerRepository extends JpaRepository<EvaluationAnswer, Long> {

    // Respuestas de un intento, en el orden en que se registraron.
    List<EvaluationAnswer> findByAttemptOrderByAnsweredAtAsc(EvaluationAttempt attempt);

    // Respuesta previa de una pregunta dentro de un intento (para actualizar en vez de duplicar).
    Optional<EvaluationAnswer> findByAttemptAndQuestion(EvaluationAttempt attempt, EvaluationQuestion question);

    /**
     * Proyección mínima para el registro de uso por estudiante: solo el estudiante, el
     * veredicto y la fecha. Nunca carga el texto de la respuesta ni la opción marcada.
     */
    interface AnswerCorrectnessView {
        Long getStudentId();

        Boolean getCorrect();

        LocalDateTime getAnsweredAt();
    }

    /** Proyección mínima de las respuestas con retroalimentación del docente. */
    interface AnswerFeedbackView {
        Long getStudentId();

        LocalDateTime getReviewedAt();
    }

    // Aciertos/errores por estudiante (registro de uso): solo respuestas ya corregidas
    // (correct no nulo); las preguntas abiertas sin revisar no cuentan como acierto ni error.
    @Query("""
            select a.attempt.student.id as studentId, a.correct as correct, a.answeredAt as answeredAt
            from EvaluationAnswer a
            where a.correct is not null
            """)
    List<AnswerCorrectnessView> findCorrectnessStats();

    // Respuestas con retroalimentación escrita por el docente (conteo por estudiante).
    @Query("""
            select a.attempt.student.id as studentId, a.reviewedAt as reviewedAt
            from EvaluationAnswer a
            where a.teacherFeedback is not null
            """)
    List<AnswerFeedbackView> findTeacherFeedbackStats();
}
