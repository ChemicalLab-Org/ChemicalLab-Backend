package com.morales.chemicallab.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Respuesta paginada de logs con una forma estable y simple para el frontend, en lugar
 * de exponer directamente la estructura interna de {@link Page} de Spring Data.
 */
public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static AuditLogPageResponse from(Page<AuditLogResponse> page) {
        return new AuditLogPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
