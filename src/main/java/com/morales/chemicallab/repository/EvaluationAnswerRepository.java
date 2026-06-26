package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.EvaluationAnswer;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationAnswerRepository extends JpaRepository<EvaluationAnswer, Long> {

    // Respuestas de un intento, en el orden en que se registraron.
    List<EvaluationAnswer> findByAttemptOrderByAnsweredAtAsc(EvaluationAttempt attempt);

    // Respuesta previa de una pregunta dentro de un intento (para actualizar en vez de duplicar).
    Optional<EvaluationAnswer> findByAttemptAndQuestion(EvaluationAttempt attempt, EvaluationQuestion question);
}
