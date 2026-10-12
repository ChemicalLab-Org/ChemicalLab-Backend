package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AuditLogPageResponse;
import com.morales.chemicallab.dto.AuditLogResponse;
import com.morales.chemicallab.dto.AuditLogSummaryResponse;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Endpoints de consulta de logs/trazabilidad para el panel administrativo. Son de solo
 * lectura y están restringidos al rol ADMINISTRADOR en {@code SecurityConfig}
 * ({@code /api/admin/**}). No se exponen contraseñas ni tokens: el contenido proviene de
 * registros que por diseño nunca almacenan datos sensibles.
 */
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogService auditLogService;
    private final com.morales.chemicallab.security.storage.QueryLimits queryLimits;

    @GetMapping
    public AuditLogPageResponse listarLogs(
            @RequestParam(required = false) LogCategory category,
            @RequestParam(required = false) LogEventType eventType,
            @RequestParam(required = false) LogSeverity severity,
            @RequestParam(required = false) Role actorRole,
            @RequestParam(required = false) String search,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        queryLimits.page(page,size);
        search=com.morales.chemicallab.security.storage.QueryLimits.search(search);
        int safePage=page;
        int safeSize=size;
        Pageable pageable = PageRequest.of(safePage, safeSize,org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"createdAt","id"));

        return AuditLogPageResponse.from(
                auditLogService.listLogs(category, eventType, severity, actorRole, search, from, to, pageable));
    }

    @GetMapping("/{id}")
    public AuditLogResponse verLog(@PathVariable Long id) {
        return auditLogService.getLogById(id);
    }

    @GetMapping("/summary")
    public AuditLogSummaryResponse resumen() {
        return auditLogService.getSummary();
    }
}
