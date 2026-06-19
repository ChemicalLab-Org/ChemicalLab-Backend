package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.StudentProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EvaluationAttemptRepository extends JpaRepository<EvaluationAttempt, Long> {

    // Cuenta de intentos de un estudiante en una evaluación (control de maxAttempts).
    long countByEvaluationAndStudent(Evaluation evaluation, StudentProfile student);

    // Intento en un estado concreto (se usa para impedir más de uno IN_PROGRESS).
    Optional<EvaluationAttempt> findByEvaluationAndStudentAndStatus(Evaluation evaluation,
                                                                    StudentProfile student,
                                                                    AttemptStatus status);

    // Último intento del estudiante en la evaluación (para informar su estado al listar).
    Optional<EvaluationAttempt> findFirstByEvaluationAndStudentOrderByAttemptNumberDesc(Evaluation evaluation,
                                                                                        StudentProfile student);
}
