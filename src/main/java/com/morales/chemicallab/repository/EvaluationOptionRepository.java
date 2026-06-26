package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.EvaluationOption;
import com.morales.chemicallab.entity.EvaluationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvaluationOptionRepository extends JpaRepository<EvaluationOption, Long> {

    // Alternativas activas de una pregunta, en su orden de presentación.
    List<EvaluationOption> findByQuestionAndActiveTrueOrderByOrderIndexAsc(EvaluationQuestion question);
}
