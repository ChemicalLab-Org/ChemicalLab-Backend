package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Observation is independent of interaction. Every call reads the current shared database. */
@Service
@RequiredArgsConstructor
public class WhiteboardRealtimeAccess {
    private final WhiteboardSessionRepository boards;
    private final StudentProfileRepository students;
    private final WhiteboardParticipantRepository participants;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public void observe(SessionPrincipal actor, long boardId) {
        var board = boards.findById(boardId).orElseThrow(WhiteboardRealtimeAccess::denied);
        if (board.getStatus() == WhiteboardSessionStatus.CLOSED) throw denied();
        if (actor.role() == Role.DOCENTE && board.getTeacher().getUser().getId().equals(actor.userId())) return;
        if (actor.role() == Role.ESTUDIANTE) {
            var student = students.findByUser_Id(actor.userId()).orElseThrow(WhiteboardRealtimeAccess::denied);
            if (board.getGrade().equals(student.getGrade()) && board.getSection().equals(student.getSection())
                    && participants.findBySessionAndStudent(board, student).isPresent()) return;
        }
        throw denied();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("BOARD_ACCESS_LOST"); }
}
