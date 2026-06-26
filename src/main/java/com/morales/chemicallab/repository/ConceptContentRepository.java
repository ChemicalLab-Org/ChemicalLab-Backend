package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.ConceptContent;
import com.morales.chemicallab.entity.ConceptStatus;
import com.morales.chemicallab.entity.TeacherProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConceptContentRepository extends JpaRepository<ConceptContent, Long> {

    // Contenidos de un docente, más recientes primero.
    List<ConceptContent> findByCreatedByTeacherOrderByCreatedAtDesc(TeacherProfile teacher);

    // Métricas administrativas: conteo por estado y últimos contenidos creados.
    long countByStatus(ConceptStatus status);

    List<ConceptContent> findTop5ByOrderByCreatedAtDesc();

    // Búsqueda de un contenido propio del docente (control de pertenencia).
    Optional<ConceptContent> findByIdAndCreatedByTeacher(Long id, TeacherProfile teacher);

    // Categorías ya usadas por un docente (para sugerirlas al crear nuevos contenidos).
    @Query("""
            select distinct c.category from ConceptContent c
            where c.createdByTeacher = :teacher
              and c.category is not null
            """)
    List<String> findDistinctCategoriesByTeacher(@Param("teacher") TeacherProfile teacher);

    // Contenidos publicados y activos asignados a un grado/sección (vista del estudiante).
    @Query("""
            select distinct c from ConceptAssignment a
            join a.conceptContent c
            where a.grade = :grade
              and a.section = :section
              and a.active = true
              and c.status = :status
              and c.active = true
            order by c.createdAt desc
            """)
    List<ConceptContent> findPublishedForSection(@Param("grade") String grade,
                                                 @Param("section") String section,
                                                 @Param("status") ConceptStatus status);

    // Un contenido publicado y activo concreto, solo si está asignado al grado/sección del estudiante.
    @Query("""
            select distinct c from ConceptAssignment a
            join a.conceptContent c
            where c.id = :conceptId
              and a.grade = :grade
              and a.section = :section
              and a.active = true
              and c.status = :status
              and c.active = true
            """)
    Optional<ConceptContent> findPublishedForSectionById(@Param("conceptId") Long conceptId,
                                                         @Param("grade") String grade,
                                                         @Param("section") String section,
                                                         @Param("status") ConceptStatus status);
}
