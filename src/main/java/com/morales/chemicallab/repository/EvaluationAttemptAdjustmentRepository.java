package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationAttemptAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvaluationAttemptAdjustmentRepository
        extends JpaRepository<EvaluationAttemptAdjustment, Long> {

    // Ajustes activos de un intento (los que afectan la nota), del más antiguo al más reciente.
    List<EvaluationAttemptAdjustment> findByAttemptAndActiveTrueOrderByCreatedAtAsc(EvaluationAttempt attempt);
}
