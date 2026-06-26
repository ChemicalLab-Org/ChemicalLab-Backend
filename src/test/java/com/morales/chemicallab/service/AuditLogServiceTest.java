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
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @SuppressWarnings("unchecked")
    @Test
    void listLogs_sinFiltros_noFalla() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> page = new PageImpl<>(List.of(sampleLog()), pageable, 1);
        when(systemLogRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<AuditLogResponse> result = service.listLogs(null, null, null, null, null, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).eventType()).isEqualTo(LogEventType.LOGIN_SUCCESS);
        // El filtrado es dinámico: sin filtros igualmente se consulta con una Specification.
        verify(systemLogRepository).findAll(any(Specification.class), eq(pageable));
    }

    @SuppressWarnings("unchecked")
    @Test
    void listLogs_sinLogs_retornaPaginaVacia() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(systemLogRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

        Page<AuditLogResponse> result = service.listLogs(null, null, null, null, null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void listLogs_conCategorySystem_noFalla() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SystemLog> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(systemLogRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

        Page<AuditLogResponse> result =
                service.listLogs(LogCategory.SYSTEM, null, null, null, null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(systemLogRepository).findAll(any(Specification.class), eq(pageable));
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

    @Test
    void summary_sinLogs_retornaCeros() {
        // Sin logs todos los conteos son 0 (los mocks devuelven 0L por defecto).
        AuditLogSummaryResponse summary = service.getSummary();

        assertThat(summary.totalLogs()).isZero();
        assertThat(summary.infoCount()).isZero();
        assertThat(summary.warningCount()).isZero();
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.authEvents()).isZero();
        assertThat(summary.userEvents()).isZero();
        assertThat(summary.evaluationEvents()).isZero();
        assertThat(summary.conceptEvents()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void mapper_conCamposNull_noFalla() {
        // Un log con actor/target/metadata nulos (p. ej. login fallido) no debe romper el mapeo.
        Pageable pageable = PageRequest.of(0, 20);
        SystemLog withNulls = SystemLog.builder()
                .id(7L)
                .eventType(LogEventType.LOGIN_FAILED)
                .category(LogCategory.AUTH)
                .severity(LogSeverity.WARNING)
                .actorUserId(null)
                .actorUsername(null)
                .actorRole(null)
                .targetType(null)
                .targetId(null)
                .targetLabel(null)
                .metadata(null)
                .description("Contraseña incorrecta.")
                .createdAt(LocalDateTime.now())
                .build();
        Page<SystemLog> page = new PageImpl<>(List.of(withNulls), pageable, 1);
        when(systemLogRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<AuditLogResponse> result = service.listLogs(null, null, null, null, null, null, null, pageable);

        AuditLogResponse mapped = result.getContent().get(0);
        assertThat(mapped.id()).isEqualTo(7L);
        assertThat(mapped.actorUserId()).isNull();
        assertThat(mapped.actorUsername()).isNull();
        assertThat(mapped.actorRole()).isNull();
        assertThat(mapped.targetLabel()).isNull();
        assertThat(mapped.metadata()).isNull();
        assertThat(mapped.eventType()).isEqualTo(LogEventType.LOGIN_FAILED);
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
