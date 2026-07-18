package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.UsagePanelResponse;
import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.WhiteboardSessionStatus;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.EvaluationRepository;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.repository.UsageEventRepository;
import com.morales.chemicallab.repository.WhiteboardParticipantRepository;
import com.morales.chemicallab.repository.WhiteboardSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de indicadores del panel administrativo. Se mockean los
 * repositorios para verificar que cada indicador se calcula sobre la fuente de datos real
 * correspondiente y que el DTO expone únicamente conteos (nunca payloads ni datos sensibles).
 */
@ExtendWith(MockitoExtension.class)
class UsagePanelServiceTest {

    @Mock
    private UsageEventRepository usageEventRepository;
    @Mock
    private SystemLogRepository systemLogRepository;
    @Mock
    private WhiteboardSessionRepository whiteboardSessionRepository;
    @Mock
    private WhiteboardParticipantRepository whiteboardParticipantRepository;
    @Mock
    private EvaluationRepository evaluationRepository;
    @Mock
    private EvaluationAttemptRepository evaluationAttemptRepository;

    @InjectMocks
    private UsagePanelService service;

    @Test
    void getPanel_calculaCadaIndicadorSobreSuFuenteReal() {
        // Actividad general
        when(usageEventRepository.count()).thenReturn(120L);
        when(usageEventRepository.countDistinctUsers()).thenReturn(14L);
        when(usageEventRepository.countDistinctModulesUsed()).thenReturn(7L);
        when(usageEventRepository.countByEventType(UsageEventType.MODULE_ACCESS)).thenReturn(60L);
        when(usageEventRepository.countByEventType(UsageEventType.COMPOUND_FORMATION_USED)).thenReturn(11L);

        // Pizarra
        when(whiteboardSessionRepository.count()).thenReturn(9L);
        when(whiteboardSessionRepository.countByStatus(WhiteboardSessionStatus.ACTIVE)).thenReturn(2L);
        when(whiteboardSessionRepository.countByStatus(WhiteboardSessionStatus.CLOSED)).thenReturn(6L);
        when(whiteboardSessionRepository.countByFinalSnapshotSizeGreaterThan(0L)).thenReturn(5L);
        when(usageEventRepository.countByEventType(UsageEventType.WHITEBOARD_SESSION_JOINED)).thenReturn(31L);
        when(whiteboardParticipantRepository.countDistinctStudents()).thenReturn(12L);
        when(systemLogRepository.countByCategory(LogCategory.WHITEBOARD)).thenReturn(44L);

        // Evaluaciones
        when(evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED)).thenReturn(4L);
        when(usageEventRepository.countByEventType(UsageEventType.EVALUATION_OPENED)).thenReturn(22L);
        when(usageEventRepository.countByEventType(UsageEventType.EVALUATION_STARTED)).thenReturn(18L);
        when(evaluationAttemptRepository.count()).thenReturn(20L);
        when(evaluationAttemptRepository.countByStatusIn(argThat(this::isSubmittedStatuses))).thenReturn(17L);
        when(evaluationAttemptRepository.countByStatusIn(argThat(this::isOnlyGraded))).thenReturn(15L);
        when(usageEventRepository.countByEventType(UsageEventType.RESULTS_VIEWED)).thenReturn(25L);

        // Trazabilidad
        when(systemLogRepository.count()).thenReturn(300L);
        when(systemLogRepository.countBySeverity(LogSeverity.WARNING)).thenReturn(8L);
        when(systemLogRepository.countBySeverity(LogSeverity.ERROR)).thenReturn(1L);
        when(systemLogRepository.countByEventType(LogEventType.LOGIN_SUCCESS)).thenReturn(90L);
        when(systemLogRepository.countByEventType(LogEventType.LOGIN_FAILED)).thenReturn(3L);

        UsagePanelResponse panel = service.getPanel();

        assertThat(panel.general().totalEvents()).isEqualTo(120L);
        assertThat(panel.general().activeUsers()).isEqualTo(14L);
        assertThat(panel.general().modulesUsed()).isEqualTo(7L);
        assertThat(panel.general().moduleAccessEvents()).isEqualTo(60L);
        assertThat(panel.general().compoundFormationEvents()).isEqualTo(11L);

        assertThat(panel.whiteboard().sessionsCreated()).isEqualTo(9L);
        assertThat(panel.whiteboard().sessionsActive()).isEqualTo(2L);
        assertThat(panel.whiteboard().sessionsClosed()).isEqualTo(6L);
        assertThat(panel.whiteboard().sessionsWithSnapshot()).isEqualTo(5L);
        assertThat(panel.whiteboard().studentJoinEvents()).isEqualTo(31L);
        assertThat(panel.whiteboard().distinctStudents()).isEqualTo(12L);
        assertThat(panel.whiteboard().auditEvents()).isEqualTo(44L);

        assertThat(panel.evaluations().published()).isEqualTo(4L);
        assertThat(panel.evaluations().openedEvents()).isEqualTo(22L);
        assertThat(panel.evaluations().startedEvents()).isEqualTo(18L);
        assertThat(panel.evaluations().attemptsTotal()).isEqualTo(20L);
        assertThat(panel.evaluations().attemptsSubmitted()).isEqualTo(17L);
        assertThat(panel.evaluations().attemptsGraded()).isEqualTo(15L);
        assertThat(panel.evaluations().resultViewEvents()).isEqualTo(25L);

        assertThat(panel.traceability().auditTotal()).isEqualTo(300L);
        assertThat(panel.traceability().auditWarnings()).isEqualTo(8L);
        assertThat(panel.traceability().auditErrors()).isEqualTo(1L);
        assertThat(panel.traceability().loginSuccess()).isEqualTo(90L);
        assertThat(panel.traceability().loginFailed()).isEqualTo(3L);
    }

    @Test
    void getPanel_sinDatos_devuelveConteosEnCero() {
        when(evaluationAttemptRepository.countByStatusIn(anyCollection())).thenReturn(0L);

        UsagePanelResponse panel = service.getPanel();

        assertThat(panel.general().totalEvents()).isZero();
        assertThat(panel.whiteboard().sessionsCreated()).isZero();
        assertThat(panel.evaluations().attemptsSubmitted()).isZero();
        assertThat(panel.traceability().loginFailed()).isZero();
    }

    private boolean isSubmittedStatuses(Collection<AttemptStatus> statuses) {
        return statuses != null && statuses.size() == 3
                && statuses.contains(AttemptStatus.SUBMITTED)
                && statuses.contains(AttemptStatus.PENDING_MANUAL_REVIEW)
                && statuses.contains(AttemptStatus.GRADED);
    }

    private boolean isOnlyGraded(Collection<AttemptStatus> statuses) {
        return statuses != null && statuses.size() == 1 && statuses.contains(AttemptStatus.GRADED);
    }
}
