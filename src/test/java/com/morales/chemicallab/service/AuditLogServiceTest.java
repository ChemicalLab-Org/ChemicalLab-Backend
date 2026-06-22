package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuditLogResponse;
import com.morales.chemicallab.dto.AuditLogSummaryResponse;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de trazabilidad. Se mockean el repositorio y el escritor
 * para validar el registro de eventos, la consulta paginada, los filtros, el resumen y la
 * robustez ante fallos de logging, sin tocar base de datos.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private SystemLogRepository systemLogRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private AuditLogWriter auditLogWriter;

    @InjectMocks
    private AuditLogService service;

    // =========================================================================
    // REGISTRO DE EVENTOS
    // =========================================================================

    @Test
    void recordInfo_persisteEventoConSeveridadInfo() {
        service.recordInfo(LogEventType.USER_CREATED, "UserAccount", 5L, "Ana Pérez",
                "Registrar estudiante", "Se registró un nuevo estudiante.", "role=ESTUDIANTE");

        SystemLog saved = capturePersisted();
        assertThat(saved.getEventType()).isEqualTo(LogEventType.USER_CREATED);
        assertThat(saved.getCategory()).isEqualTo(LogCategory.USER_MANAGEMENT);
        assertThat(saved.getSeverity()).isEqualTo(LogSeverity.INFO);
        assertThat(saved.getTargetId()).isEqualTo(5L);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void recordLoginFailed_noGuardaContrasenaYUsaWarning() {
        service.recordLoginFailed("usuario.intentado", "Contraseña incorrecta.");

        SystemLog saved = capturePersisted();
        assertThat(saved.getEventType()).isEqualTo(LogEventType.LOGIN_FAILED);
        assertThat(saved.getCategory()).isEqualTo(LogCategory.AUTH);
        assertThat(saved.getSeverity()).isEqualTo(LogSeverity.WARNING);
        assertThat(saved.getActorUserId()).isNull();
        assertThat(saved.getActorUsername()).isEqualTo("usuario.intentado");
        // El intento fallido nunca debe guardar la contraseña ingresada.
        assertThat(saved.getDescription()).doesNotContainIgnoringCase("password");
        assertThat(saved.getMetadata()).isNull();
    }

    @Test
    void recordWarning_passwordReset_noFiltraContrasenaTemporal() {
        // Reproduce la llamada que hacen los servicios al restablecer una contraseña:
        // jamás se pasa la contraseña temporal generada, solo datos no sensibles.
        service.recordWarning(LogEventType.PASSWORD_RESET, "UserAccount", 9L, "EST0009",
                "Restablecer contraseña", "Se restableció la contraseña del usuario EST0009.",
                "role=ESTUDIANTE");

        SystemLog saved = capturePersisted();
        assertThat(saved.getEventType()).isEqualTo(LogEventType.PASSWORD_RESET);
        assertThat(saved.getSeverity()).isEqualTo(LogSeverity.WARNING);
        assertThat(saved.getMetadata()).isEqualTo("role=ESTUDIANTE");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("password");
        assertThat(saved.getDescription()).doesNotContainIgnoringCase("password");
    }

    @Test
    void safePersist_noPropagaErroresDeLogging() {
        doThrow(new RuntimeException("BD no disponible")).when(auditLogWriter).persist(any());

        // Un fallo al guardar el log no debe romper la operación que lo originó.
        assertThatCode(() -> service.recordInfo(LogEventType.EVALUATION_CREATED, "Evaluation",
                1L, "Examen", "Crear evaluación", "Se creó una evaluación.", null))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // CONSULTA
    // =========================================================================

    @Test
    void listLogs_devuelvePaginaMapeada() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> page = new PageImpl<>(List.of(sampleLog()), pageable, 1);
        when(systemLogRepository.search(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(pageable))).thenReturn(page);

        Page<AuditLogResponse> result = service.listLogs(null, null, null, null, null, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).eventType()).isEqualTo(LogEventType.LOGIN_SUCCESS);
    }

    @Test
    void listLogs_filtraPorCategoria() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> page = new PageImpl<>(List.of(sampleLog()), pageable, 1);
        when(systemLogRepository.search(eq(LogCategory.AUTH), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(pageable))).thenReturn(page);

        Page<AuditLogResponse> result =
                service.listLogs(LogCategory.AUTH, null, null, null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(systemLogRepository).search(eq(LogCategory.AUTH), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    void listLogs_filtraPorSeveridad() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> page = new PageImpl<>(List.of(sampleLog()), pageable, 1);
        when(systemLogRepository.search(isNull(), isNull(), eq(LogSeverity.WARNING), isNull(),
                isNull(), isNull(), isNull(), eq(pageable))).thenReturn(page);

        Page<AuditLogResponse> result =
                service.listLogs(null, null, LogSeverity.WARNING, null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(systemLogRepository).search(isNull(), isNull(), eq(LogSeverity.WARNING), isNull(),
                isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    void getSummary_cuentaPorSeveridadYCategoria() {
        when(systemLogRepository.count()).thenReturn(20L);
        when(systemLogRepository.countBySeverity(LogSeverity.INFO)).thenReturn(15L);
        when(systemLogRepository.countBySeverity(LogSeverity.WARNING)).thenReturn(4L);
        when(systemLogRepository.countBySeverity(LogSeverity.ERROR)).thenReturn(1L);
        when(systemLogRepository.countByCategory(LogCategory.AUTH)).thenReturn(8L);
        when(systemLogRepository.countByCategory(LogCategory.USER_MANAGEMENT)).thenReturn(5L);
        when(systemLogRepository.countByCategory(LogCategory.EVALUATION)).thenReturn(4L);
        when(systemLogRepository.countByCategory(LogCategory.CONCEPT_CONTENT)).thenReturn(3L);

        AuditLogSummaryResponse summary = service.getSummary();

        assertThat(summary.totalLogs()).isEqualTo(20L);
        assertThat(summary.infoCount()).isEqualTo(15L);
        assertThat(summary.warningCount()).isEqualTo(4L);
        assertThat(summary.errorCount()).isEqualTo(1L);
        assertThat(summary.authEvents()).isEqualTo(8L);
        assertThat(summary.userEvents()).isEqualTo(5L);
        assertThat(summary.evaluationEvents()).isEqualTo(4L);
        assertThat(summary.conceptEvents()).isEqualTo(3L);
    }

    // =========================================================================
    // APOYO
    // =========================================================================

    private SystemLog capturePersisted() {
        ArgumentCaptor<SystemLog> captor = ArgumentCaptor.forClass(SystemLog.class);
        verify(auditLogWriter).persist(captor.capture());
        return captor.getValue();
    }

    private SystemLog sampleLog() {
        return SystemLog.builder()
                .id(1L)
                .eventType(LogEventType.LOGIN_SUCCESS)
                .category(LogCategory.AUTH)
                .severity(LogSeverity.INFO)
                .actorUsername("admin")
                .description("Inicio de sesión correcto.")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
