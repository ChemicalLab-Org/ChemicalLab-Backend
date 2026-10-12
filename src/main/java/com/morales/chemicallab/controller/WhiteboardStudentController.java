package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.WhiteboardBoardStateResponse;
import com.morales.chemicallab.dto.WhiteboardHistoryItemResponse;
import com.morales.chemicallab.dto.WhiteboardSnapshotDownload;
import com.morales.chemicallab.dto.WhiteboardStudentSessionResponse;
import com.morales.chemicallab.service.WhiteboardSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints del estudiante para la pizarra interactiva. El acceso por rol se controla en
 * SecurityConfig ({@code /api/whiteboards/student/**} → ESTUDIANTE); el estudiante se
 * identifica del usuario autenticado y solo ve/usa sesiones de su grado/sección.
 */
@RestController
@RequestMapping("/api/whiteboards/student")
@RequiredArgsConstructor
public class WhiteboardStudentController {

    private final WhiteboardSessionService whiteboardSessionService;

    @GetMapping("/active")
    public com.morales.chemicallab.dto.QueryPage<WhiteboardStudentSessionResponse> listarSesionesDisponibles(Authentication authentication, @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,@org.springframework.web.bind.annotation.RequestParam(defaultValue="25") int size) {
        return whiteboardSessionService.listActiveForStudent(authentication.getName(),page,size);
    }

    @PostMapping("/{id}/join")
    public WhiteboardStudentSessionResponse unirse(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.joinSession(authentication.getName(), id);
    }

    @GetMapping("/history")
    public com.morales.chemicallab.dto.QueryPage<WhiteboardHistoryItemResponse> verHistorial(Authentication authentication, @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,@org.springframework.web.bind.annotation.RequestParam(defaultValue="25") int size) {
        return whiteboardSessionService.getStudentHistory(authentication.getName(),page,size);
    }

    @GetMapping("/{id}")
    public WhiteboardStudentSessionResponse verSesion(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.getStudentSessionDetail(authentication.getName(), id);
    }

    @GetMapping("/{id}/state")
    public WhiteboardBoardStateResponse obtenerEstado(
            Authentication authentication,
            @PathVariable Long id) {
        return whiteboardSessionService.getStudentBoardState(authentication.getName(), id);
    }

    @GetMapping("/{id}/snapshot")
    public ResponseEntity<byte[]> obtenerCaptura(
            Authentication authentication,
            @PathVariable Long id) {
        WhiteboardSnapshotDownload download =
                whiteboardSessionService.getStudentSnapshot(authentication.getName(), id);
        return WhiteboardTeacherController.snapshotResponse(download);
    }
}
