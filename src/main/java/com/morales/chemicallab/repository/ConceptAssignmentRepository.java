package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.ConceptAssignment;
import com.morales.chemicallab.entity.ConceptContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConceptAssignmentRepository extends JpaRepository<ConceptAssignment, Long> {

    // Asignaciones de un contenido, ordenadas por fecha de asignación.
    List<ConceptAssignment> findByConceptContentOrderByAssignedAtDesc(ConceptContent conceptContent);

    // Valida si ya existe una asignación activa para el mismo grado/sección (evita duplicados).
    boolean existsByConceptContentAndGradeAndSectionAndActiveTrue(ConceptContent conceptContent,
                                                                  String grade,
                                                                  String section);
}
