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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Indicadores agregados del panel administrativo de métricas.
 *
 * <p>Combina las fuentes de datos <strong>reales</strong> que ya persiste la plataforma:
 * eventos de uso ({@code usage_events}), logs de trazabilidad ({@code system_logs}),
 * sesiones y participantes de pizarra, y evaluaciones con sus intentos. Todo son conteos:
 * nunca se exponen payloads, respuestas de estudiantes, trazos de pizarra ni credenciales.</p>
 *
 * <p>Está separado de {@link UsageMetricService} (que registra y resume los eventos de uso)
 * porque este servicio consulta además otras tablas de dominio para sustentar los
 * indicadores del Project Charter en el panel del administrador.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsagePanelService {

    /** Estados que cuentan como intento enviado (incluye los ya calificados). */
    private static final List<AttemptStatus> SUBMITTED_STATUSES = List.of(
            AttemptStatus.SUBMITTED, AttemptStatus.PENDING_MANUAL_REVIEW, AttemptStatus.GRADED);

    private final UsageEventRepository usageEventRepository;
    private final SystemLogRepository systemLogRepository;
    private final WhiteboardSessionRepository whiteboardSessionRepository;
    private final WhiteboardParticipantRepository whiteboardParticipantRepository;
    private final EvaluationRepository evaluationRepository;
    private final EvaluationAttemptRepository evaluationAttemptRepository;

    public UsagePanelResponse getPanel() {
        return new UsagePanelResponse(
                buildGeneral(),
                buildWhiteboard(),
                buildEvaluations(),
                buildTraceability());
    }

    private UsagePanelResponse.GeneralStats buildGeneral() {
        return new UsagePanelResponse.GeneralStats(
                usageEventRepository.count(),
                usageEventRepository.countDistinctUsers(),
                usageEventRepository.countDistinctModulesUsed(),
                usageEventRepository.countByEventType(UsageEventType.MODULE_ACCESS),
                usageEventRepository.countByEventType(UsageEventType.COMPOUND_FORMATION_USED));
    }

    private UsagePanelResponse.WhiteboardStats buildWhiteboard() {
        return new UsagePanelResponse.WhiteboardStats(
                whiteboardSessionRepository.count(),
                whiteboardSessionRepository.countByStatus(WhiteboardSessionStatus.ACTIVE),
                whiteboardSessionRepository.countByStatus(WhiteboardSessionStatus.CLOSED),
                whiteboardSessionRepository.countByFinalSnapshotSizeGreaterThan(0L),
                usageEventRepository.countByEventType(UsageEventType.WHITEBOARD_SESSION_JOINED),
                whiteboardParticipantRepository.countDistinctStudents(),
                systemLogRepository.countByCategory(LogCategory.WHITEBOARD));
    }

    private UsagePanelResponse.EvaluationStats buildEvaluations() {
        return new UsagePanelResponse.EvaluationStats(
                evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED),
                usageEventRepository.countByEventType(UsageEventType.EVALUATION_OPENED),
                usageEventRepository.countByEventType(UsageEventType.EVALUATION_STARTED),
                evaluationAttemptRepository.count(),
                evaluationAttemptRepository.countByStatusIn(SUBMITTED_STATUSES),
                evaluationAttemptRepository.countByStatusIn(List.of(AttemptStatus.GRADED)),
                usageEventRepository.countByEventType(UsageEventType.RESULTS_VIEWED));
    }

    private UsagePanelResponse.TraceabilityStats buildTraceability() {
        return new UsagePanelResponse.TraceabilityStats(
                systemLogRepository.count(),
                systemLogRepository.countBySeverity(LogSeverity.WARNING),
                systemLogRepository.countBySeverity(LogSeverity.ERROR),
                systemLogRepository.countByEventType(LogEventType.LOGIN_SUCCESS),
                systemLogRepository.countByEventType(LogEventType.LOGIN_FAILED));
    }
}
