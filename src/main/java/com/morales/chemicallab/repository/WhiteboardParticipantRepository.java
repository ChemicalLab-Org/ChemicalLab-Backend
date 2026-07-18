package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.WhiteboardParticipant;
import com.morales.chemicallab.entity.WhiteboardSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WhiteboardParticipantRepository extends JpaRepository<WhiteboardParticipant, Long> {

    // Participantes de una sesión, en orden de unión.
    List<WhiteboardParticipant> findBySessionOrderByJoinedAtAsc(WhiteboardSession session);

    // Participante concreto de una sesión (para join idempotente y permiso individual).
    Optional<WhiteboardParticipant> findBySessionAndStudent(WhiteboardSession session, StudentProfile student);

    Optional<WhiteboardParticipant> findBySession_IdAndStudent_Id(Long sessionId, Long studentId);

    long countBySession(WhiteboardSession session);

    // Total de participantes (resumen institucional del administrador).
    long count();

    // Estudiantes distintos que se han unido al menos a una sesión de pizarra.
    @Query("select count(distinct p.student.id) from WhiteboardParticipant p")
    long countDistinctStudents();
}
