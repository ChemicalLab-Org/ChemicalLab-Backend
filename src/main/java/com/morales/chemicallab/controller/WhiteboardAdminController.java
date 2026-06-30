package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.WhiteboardAdminSessionResponse;
import com.morales.chemicallab.dto.WhiteboardSummaryResponse;
import com.morales.chemicallab.service.WhiteboardSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de supervisión institucional de la pizarra para el administrador. El acceso se
 * controla en SecurityConfig ({@code /api/whiteboards/admin/**} → ADMINISTRADOR). El
 * administrador solo consulta metadata: no dibuja ni edita en el MVP.
 */
@RestController
@RequestMapping("/api/whiteboards/admin")
@RequiredArgsConstructor
public class WhiteboardAdminController {

    private final WhiteboardSessionService whiteboardSessionService;

    @GetMapping("/summary")
    public WhiteboardSummaryResponse resumen() {
        return whiteboardSessionService.getAdminSummary();
    }

    @GetMapping
    public List<WhiteboardAdminSessionResponse> listar() {
        return whiteboardSessionService.listAdminSessions();
    }
}
