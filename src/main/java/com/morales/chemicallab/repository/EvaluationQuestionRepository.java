package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvaluationQuestionRepository extends JpaRepository<EvaluationQuestion, Long> {

    // Preguntas activas de una evaluación, en su orden de presentación.
    List<EvaluationQuestion> findByEvaluationAndActiveTrueOrderByOrderIndexAsc(Evaluation evaluation);

    // Cuenta de preguntas activas (se usa para impedir publicar evaluaciones vacías).
    long countByEvaluationAndActiveTrue(Evaluation evaluation);
}
