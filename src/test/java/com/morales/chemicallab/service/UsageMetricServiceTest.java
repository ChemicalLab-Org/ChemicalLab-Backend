package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.RecordUsageEventRequest;
import com.morales.chemicallab.dto.UsageEventResponse;
import com.morales.chemicallab.dto.UsageMetricsSummaryResponse;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UsageEventRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de métricas de uso. Se mockean los repositorios para
 * validar que el actor y el rol provienen del usuario autenticado (no de la petición), que
 * la metadata sensible no se almacena y que el resumen y los eventos recientes se calculan
 * sobre DTOs seguros, sin tocar base de datos.
 */
@ExtendWith(MockitoExtension.class)
class UsageMetricServiceTest {

    @Mock
    private UsageEventRepository usageEventRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private UsageMetricService service;

    // =========================================================================
    // REGISTRO
    // =========================================================================

    @Test
    void recordEvent_usaUsuarioYRolDelTokenNoDeLaPeticion() {
        UserAccount estudiante = user(42L, "EST0042", Role.ESTUDIANTE);
        when(userAccountRepository.findByUsername("EST0042")).thenReturn(Optional.of(estudiante));

        RecordUsageEventRequest request = new RecordUsageEventRequest(
                UsageModule.PERIODIC_TABLE, UsageEventType.PERIODIC_ELEMENT_VIEWED,
                "ELEMENT", "Na", "El estudiante abrió un elemento.", null);

        service.recordEvent("EST0042", request);

        UsageEvent saved = capturePersisted();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getUsername()).isEqualTo("EST0042");
        assertThat(saved.getUserRole()).isEqualTo(Role.ESTUDIANTE);
        assertThat(saved.getModule()).isEqualTo(UsageModule.PERIODIC_TABLE);
        assertThat(saved.getEventType()).isEqualTo(UsageEventType.PERIODIC_ELEMENT_VIEWED);
        assertThat(saved.getResourceId()).isEqualTo("Na");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void recordEvent_descartaMetadataSensible() {
        UserAccount estudiante = user(7L, "EST0007", Role.ESTUDIANTE);
        when(userAccountRepository.findByUsername("EST0007")).thenReturn(Optional.of(estudiante));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("evaluationId", "15");
        metadata.put("password", "secreta123");
        metadata.put("token", "eyJhbGciOi...");
        metadata.put("correctAnswer", "Opción B");
        metadata.put("respuestaSeleccionada", "Opción C");

        RecordUsageEventRequest request = new RecordUsageEventRequest(
                UsageModule.EVALUATIONS, UsageEventType.EVALUATION_OPENED,
                "EVALUATION", "15", null, metadata);

        service.recordEvent("EST0007", request);

        UsageEvent saved = capturePersisted();
        // Solo el dato seguro sobrevive; nunca contraseñas, tokens ni respuestas.
        assertThat(saved.getMetadata()).contains("evaluationId=15");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("password");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("secreta");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("token");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("answer");
        assertThat(saved.getMetadata()).doesNotContainIgnoringCase("respuesta");
    }

    @Test
    void recordEvent_metadataSoloSensible_quedaNula() {
        UserAccount estudiante = user(7L, "EST0007", Role.ESTUDIANTE);
        when(userAccountRepository.findByUsername("EST0007")).thenReturn(Optional.of(estudiante));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("password", "secreta123");
        metadata.put("alternativaCorrecta", "B");

        RecordUsageEventRequest request = new RecordUsageEventRequest(
                UsageModule.EVALUATIONS, UsageEventType.EVALUATION_STARTED,
                "EVALUATION", "15", null, metadata);

        service.recordEvent("EST0007", request);

        assertThat(capturePersisted().getMetadata()).isNull();
    }

    @Test
    void recordEvent_eventoDeCompuesto_guardaMetadataSegura() {
        UserAccount estudiante = user(7L, "EST0007", Role.ESTUDIANTE);
        when(userAccountRepository.findByUsername("EST0007")).thenReturn(Optional.of(estudiante));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("compoundType", "SAL_BINARIA");
        metadata.put("success", "true");
        metadata.put("selectedElementsCount", "2");

        RecordUsageEventRequest request = new RecordUsageEventRequest(
                UsageModule.COMPOUNDS, UsageEventType.COMPOUND_FORMATION_USED,
                null, null, null, metadata);

        service.recordEvent("EST0007", request);

        UsageEvent saved = capturePersisted();
        assertThat(saved.getEventType()).isEqualTo(UsageEventType.COMPOUND_FORMATION_USED);
        assertThat(saved.getMetadata()).contains("compoundType=SAL_BINARIA");
        assertThat(saved.getMetadata()).contains("success=true");
        assertThat(saved.getMetadata()).contains("selectedElementsCount=2");
    }

    @Test
    void recordEvent_usuarioInexistente_lanzaError() {
        when(userAccountRepository.findByUsername("fantasma")).thenReturn(Optional.empty());

        RecordUsageEventRequest request = new RecordUsageEventRequest(
                UsageModule.DASHBOARD, UsageEventType.MODULE_ACCESS, null, null, null, null);

        assertThatThrownBy(() -> service.recordEvent("fantasma", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(usageEventRepository, never()).save(any());
    }

    // =========================================================================
    // CONSULTA
    // =========================================================================

    @SuppressWarnings("unchecked")
    @Test
    void getSummary_agrupaTotalesPorModuloTipoYRol() {
        when(usageEventRepository.count(any(Specification.class))).thenReturn(5L);

        UsageMetricsSummaryResponse summary = service.getSummary(null, null);

        assertThat(summary.totalEvents()).isEqualTo(5L);
        assertThat(summary.byModule()).isNotEmpty();
        assertThat(summary.byEventType()).isNotEmpty();
        assertThat(summary.byRole()).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSummary_sinEventos_retornaTotalCeroYListasVacias() {
        when(usageEventRepository.count(any(Specification.class))).thenReturn(0L);

        UsageMetricsSummaryResponse summary = service.getSummary(null, null);

        assertThat(summary.totalEvents()).isZero();
        assertThat(summary.byModule()).isEmpty();
        assertThat(summary.byEventType()).isEmpty();
        assertThat(summary.byRole()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getRecentEvents_devuelveDtoSeguro() {
        UsageEvent event = UsageEvent.builder()
                .id(1L)
                .userId(3L)
                .username("DOC0003")
                .userRole(Role.DOCENTE)
                .module(UsageModule.RESULTS)
                .eventType(UsageEventType.RESULTS_VIEWED)
                .resourceType("EVALUATION")
                .resourceId("9")
                .description("El docente revisó resultados.")
                .metadata("evaluationId=9")
                .occurredAt(LocalDateTime.now())
                .build();
        Page<UsageEvent> page = new PageImpl<>(List.of(event), Pageable.ofSize(20), 1);
        when(usageEventRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        List<UsageEventResponse> recent = service.getRecentEvents(20, null, null, null);

        assertThat(recent).hasSize(1);
        UsageEventResponse dto = recent.get(0);
        assertThat(dto.username()).isEqualTo("DOC0003");
        assertThat(dto.userRole()).isEqualTo(Role.DOCENTE);
        assertThat(dto.module()).isEqualTo(UsageModule.RESULTS);
        assertThat(dto.eventType()).isEqualTo(UsageEventType.RESULTS_VIEWED);
        assertThat(dto.metadata()).isEqualTo("evaluationId=9");
    }

    // =========================================================================
    // APOYO
    // =========================================================================

    private UsageEvent capturePersisted() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usageEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private UserAccount user(Long id, String username, Role role) {
        return UserAccount.builder()
                .id(id)
                .username(username)
                .role(role)
                .active(true)
                .build();
    }
}
