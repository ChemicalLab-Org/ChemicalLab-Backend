package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.RecordUsageEventRequest;
import com.morales.chemicallab.service.UsageMetricService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de métricas de uso e interacción.
 *
 * <p>Disponible para cualquier usuario autenticado (ESTUDIANTE, DOCENTE o ADMINISTRADOR):
 * cada usuario registra únicamente sus propias interacciones. El usuario y el rol se
 * resuelven del token ({@link Authentication}), nunca de datos enviados por el cliente, de
 * modo que el frontend no puede falsificar la identidad ni el rol.</p>
 *
 * <p>Estas métricas son independientes de los logs de auditoría: miden la interacción
 * educativa, no acciones críticas de seguridad.</p>
 */
@RestController
@RequestMapping("/api/usage-metrics")
@RequiredArgsConstructor
public class UsageMetricController {

    private final UsageMetricService usageMetricService;

    @PostMapping("/events")
    public ResponseEntity<Void> registrarEvento(
            Authentication authentication,
            @Valid @RequestBody RecordUsageEventRequest request) {
        usageMetricService.recordEvent(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
