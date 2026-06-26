package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.SystemHealthResponse;
import com.morales.chemicallab.service.SystemHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final SystemHealthService systemHealthService;

    public HealthController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    /**
     * Devuelve siempre HTTP 200 mientras el backend esté activo.
     * El campo {@code status} del cuerpo indica el estado combinado:
     * "UP" (todo disponible) o "DEGRADED" (backend activo, BD con problemas).
     * El cliente puede así distinguir entre "backend caído" y "BD degradada".
     */
    @GetMapping("/api/health")
    public ResponseEntity<SystemHealthResponse> health() {
        return ResponseEntity.ok(systemHealthService.check());
    }
}
