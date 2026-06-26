package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuditLogResponse;
import com.morales.chemicallab.dto.AuditLogSummaryResponse;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pruebas de integración del listado de logs contra la base de datos real.
 *
 * <p>Garantizan que la consulta del visor de logs se ejecute correctamente aunque no haya
 * filtros ni registros, evitando la regresión por la que PostgreSQL respondía
 * «could not determine data type of parameter» (HTTP 500) con la antigua consulta JPQL que
 * comparaba parámetros opcionales con {@code IS NULL}.</p>
 */
@SpringBootTest
class AuditLogQueryDbTest {

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void listLogs_sinFiltros_ejecutaConsultaSinError() {
        assertThatCode(() -> {
            Page<AuditLogResponse> page =
                    auditLogService.listLogs(null, null, null, null, null, null, null, PageRequest.of(0, 20));
            assertThat(page).isNotNull();
            assertThat(page.getContent()).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void listLogs_conCategoriaYSeveridad_ejecutaConsultaSinError() {
        assertThatCode(() -> {
            auditLogService.listLogs(LogCategory.SYSTEM, null, LogSeverity.INFO, null, null, null, null,
                    PageRequest.of(0, 20));
            auditLogService.listLogs(LogCategory.AUTH, null, null, null, "admin", null, null,
                    PageRequest.of(0, 20));
        }).doesNotThrowAnyException();
    }

    @Test
    void summary_ejecutaConsultaSinError() {
        assertThatCode(() -> {
            AuditLogSummaryResponse summary = auditLogService.getSummary();
            assertThat(summary).isNotNull();
            assertThat(summary.totalLogs()).isGreaterThanOrEqualTo(0L);
        }).doesNotThrowAnyException();
    }
}
