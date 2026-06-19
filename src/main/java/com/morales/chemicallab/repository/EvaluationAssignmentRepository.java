package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAssignment;
import com.morales.chemicallab.entity.EvaluationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationAssignmentRepository extends JpaRepository<EvaluationAssignment, Long> {

    // Asignaciones de una evaluación, ordenadas por fecha de asignación.
    List<EvaluationAssignment> findByEvaluationOrderByAssignedAtDesc(Evaluation evaluation);

    // Valida si ya existe una asignación activa para el mismo grado/sección (evita duplicados).
    boolean existsByEvaluationAndGradeAndSectionAndActiveTrue(Evaluation evaluation,
                                                              String grade,
                                                              String section);

    // Asignaciones activas de evaluaciones publicadas y activas para un grado/sección
    // (vista del estudiante). Cada asignación trae su evaluación asociada.
    @Query("""
            select a from EvaluationAssignment a
            join a.evaluation e
            where a.grade = :grade
              and a.section = :section
              and a.active = true
              and e.status = :status
              and e.active = true
            order by a.assignedAt desc
            """)
    List<EvaluationAssignment> findActiveForSection(@Param("grade") String grade,
                                                    @Param("section") String section,
                                                    @Param("status") EvaluationStatus status);

    // Asignación activa de una evaluación publicada concreta para el grado/sección del
    // estudiante. Solo devuelve resultado si la evaluación realmente le corresponde.
    @Query("""
            select a from EvaluationAssignment a
            join a.evaluation e
            where e.id = :evaluationId
              and a.grade = :grade
              and a.section = :section
              and a.active = true
              and e.status = :status
              and e.active = true
            """)
    Optional<EvaluationAssignment> findActiveForSectionByEvaluation(@Param("evaluationId") Long evaluationId,
                                                                    @Param("grade") String grade,
                                                                    @Param("section") String section,
                                                                    @Param("status") EvaluationStatus status);
}
