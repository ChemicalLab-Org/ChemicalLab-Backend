package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.TeacherProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    // Evaluaciones de un docente, más recientes primero.
    List<Evaluation> findByCreatedByTeacherOrderByCreatedAtDesc(TeacherProfile teacher);
}
