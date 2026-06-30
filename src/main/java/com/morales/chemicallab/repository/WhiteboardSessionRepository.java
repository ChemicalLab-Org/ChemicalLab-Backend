package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.WhiteboardSession;
import com.morales.chemicallab.entity.WhiteboardSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WhiteboardSessionRepository extends JpaRepository<WhiteboardSession, Long> {

    // Sesiones de un docente (activas, pausadas y cerradas), más recientes primero.
    List<WhiteboardSession> findByTeacherOrderByCreatedAtDesc(TeacherProfile teacher);

    // Sesiones de un grado/sección filtradas por estado (p. ej. ACTIVE/PAUSED para el
    // estudiante, o CLOSED para el historial), más recientes primero.
    List<WhiteboardSession> findByGradeAndSectionAndStatusInOrderByCreatedAtDesc(
            String grade, String section, Collection<WhiteboardSessionStatus> statuses);

    // Historial: sesiones cerradas de un grado/sección, ordenadas por fecha de cierre.
    List<WhiteboardSession> findByGradeAndSectionAndStatusOrderByClosedAtDesc(
            String grade, String section, WhiteboardSessionStatus status);

    // Listado supervisado para el administrador.
    List<WhiteboardSession> findAllByOrderByCreatedAtDesc();

    // Conteos para el resumen institucional del administrador.
    long countByStatus(WhiteboardSessionStatus status);
}
