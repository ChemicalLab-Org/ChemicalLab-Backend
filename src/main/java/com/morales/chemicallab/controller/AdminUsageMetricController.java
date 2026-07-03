package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.UsageCountResponse;
import com.morales.chemicallab.dto.UsageEventResponse;
import com.morales.chemicallab.dto.UsageMetricsSummaryResponse;
import com.morales.chemicallab.dto.UsagePanelResponse;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.service.UsageMetricService;
import com.morales.chemicallab.service.UsagePanelService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Consulta de métricas de uso para el panel administrativo. Solo lectura y restringido al
 * rol ADMINISTRADOR en {@code SecurityConfig} ({@code /api/admin/**}). Devuelve siempre DTOs
 * seguros: el contenido proviene de registros que por diseño nunca almacenan datos sensibles.
 */
@RestController
@RequestMapping("/api/admin/usage-metrics")
@RequiredArgsConstructor
public class AdminUsageMetricController {

    private final UsageMetricService usageMetricService;
    private final UsagePanelService usagePanelService;

    /**
     * Indicadores agregados (histórico total) para el panel administrativo: actividad
     * general, pizarra, evaluaciones y trazabilidad. Son solo conteos sobre datos reales.
     */
    @GetMapping("/panel")
    public UsagePanelResponse panel() {
        return usagePanelService.getPanel();
    }

    @GetMapping("/summary")
    public UsageMetricsSummaryResponse resumen(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return usageMetricService.getSummary(from, to);
    }

    @GetMapping("/recent")
    public List<UsageEventResponse> recientes(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UsageModule module,
            @RequestParam(required = false) UsageEventType eventType,
            @RequestParam(required = false) Role role) {
        return usageMetricService.getRecentEvents(limit, module, eventType, role);
    }

    @GetMapping("/by-module")
    public List<UsageCountResponse> porModulo(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return usageMetricService.countByModule(from, to);
    }

    @GetMapping("/by-role")
    public List<UsageCountResponse> porRol(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return usageMetricService.countByRole(from, to);
    }
}
