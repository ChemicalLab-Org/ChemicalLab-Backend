package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuditLogResponse;
import com.morales.chemicallab.dto.AuditLogSummaryResponse;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.audit.*;
import java.util.ArrayList;
import java.util.List;

/** Commit-coupled audit evidence; query contracts remain paginated and administrator-only. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS, readOnly = true)
public class AuditLogService {

    private final SystemLogRepository systemLogRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditLogWriter auditLogWriter;

    // =========================================================================
    // REGISTRO DE EVENTOS
    // =========================================================================

    /** Registra un evento informativo resolviendo el actor del contexto de seguridad. */
    public void recordInfo(LogEventType eventType, String targetType, Long targetId,
                           String targetLabel, String action, String description, String metadata) {
        recordEvent(eventType, LogSeverity.INFO, targetType, targetId, targetLabel, action, description, metadata);
    }

    /** Registra una advertencia resolviendo el actor del contexto de seguridad. */
    public void recordWarning(LogEventType eventType, String targetType, Long targetId,
                              String targetLabel, String action, String description, String metadata) {
        recordEvent(eventType, LogSeverity.WARNING, targetType, targetId, targetLabel, action, description, metadata);
    }

    /** Registra un error resolviendo el actor del contexto de seguridad. */
    public void recordError(LogEventType eventType, String targetType, Long targetId,
                            String targetLabel, String action, String description, String metadata) {
        recordEvent(eventType, LogSeverity.ERROR, targetType, targetId, targetLabel, action, description, metadata);
    }

    /** Mandatory evidence shares the calling business transaction. */
    public void recordEvent(LogEventType eventType, LogSeverity severity, String targetType,
                            Long targetId, String targetLabel, String action,
                            String description, String metadata) {
        SessionPrincipal actor = resolveAuthenticatedUser();
        SystemLog systemLog = baseBuilder(eventType, severity)
                .actorUserId(actor != null ? actor.userId() : null)
                .actorUsername(actor != null ? AuditSafety.text(actor.username(),100) : null)
                .actorRole(actor != null ? actor.role() : null)
                .targetType(AuditSafety.target(targetType))
                .targetId(targetId)
                .targetLabel(null)
                .action(eventType.name())
                .description(eventType.name())
                .metadata(AuditSafety.metadata(metadata))
                .build();
        if ("UserAccount".equals(targetType) && targetId!=null) {
            userAccountRepository.findById(targetId).ifPresent(user -> {
                String extra="credentialsVersion="+user.getCredentialsVersion();
                systemLog.setMetadata(AuditSafety.metadata((systemLog.getMetadata()==null?"":systemLog.getMetadata()+";")+extra));
            });
        }
        auditLogWriter.persist(systemLog);
        if(java.util.Set.of(LogEventType.PASSWORD_CHANGED,LogEventType.PASSWORD_RESET,LogEventType.USER_DEACTIVATED,
                LogEventType.USER_REACTIVATED,LogEventType.PERMISSIONS_CHANGED).contains(eventType)
                && (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive() || AuditContext.claimRevocation(targetId))) {
            SystemLog revocation=baseBuilder(LogEventType.SESSIONS_REVOKED,LogSeverity.INFO)
                .actorUserId(systemLog.getActorUserId()).actorUsername(systemLog.getActorUsername()).actorRole(systemLog.getActorRole())
                .targetType(systemLog.getTargetType()).targetId(targetId).action("SESSIONS_REVOKED").description("SESSIONS_REVOKED")
                .metadata(systemLog.getMetadata()).correlationId(systemLog.getCorrelationId()).createdAt(systemLog.getCreatedAt()).build();
            auditLogWriter.persist(revocation);
        }

    }

    /** Registra un inicio de sesión exitoso con el usuario autenticado como actor. */
    public void recordLoginSuccess(UserAccount user) {
        SystemLog systemLog = baseBuilder(LogEventType.LOGIN_SUCCESS, LogSeverity.INFO)
                .actorUserId(user != null ? user.getId() : null)
                .actorUsername(user != null ? AuditSafety.text(user.getUsername(),100) : null)
                .actorRole(user != null ? user.getRole() : null)
                .targetType("UserAccount").targetId(user.getId()).action("LOGIN_SUCCESS")
                .description("LOGIN_SUCCESS")
                .build();
        auditLogWriter.persist(systemLog);
    }

    // =========================================================================
    // CONSULTA (panel administrativo)
    // =========================================================================

    public Page<AuditLogResponse> listLogs(LogCategory category, LogEventType eventType,
                                           LogSeverity severity, Role actorRole, String search,
                                           LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Specification<SystemLog> spec =
                buildFilterSpecification(category, eventType, severity, actorRole, search, from, to);
        return systemLogRepository.findAll(spec, pageable).map(this::toResponse);
    }

    /**
     * Construye el filtro de forma dinámica: cada criterio se agrega como predicado solo
     * cuando tiene valor. Así la consulta nunca envía parámetros nulos sin tipo y se evita
     * el error de PostgreSQL «could not determine data type of parameter». Si no hay
     * filtros, devuelve todos los registros (lista vacía si aún no hay logs).
     */
    private Specification<SystemLog> buildFilterSpecification(LogCategory category, LogEventType eventType,
                                                              LogSeverity severity, Role actorRole, String search,
                                                              LocalDateTime from, LocalDateTime to) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim().toLowerCase() : null;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (eventType != null) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (actorRole != null) {
                predicates.add(cb.equal(root.get("actorRole"), actorRole));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (normalizedSearch != null) {
                String pattern = "%" + normalizedSearch + "%";
                Predicate byActor = cb.like(cb.lower(cb.coalesce(root.<String>get("actorUsername"), "")), pattern);
                Predicate byTarget = cb.like(cb.lower(cb.coalesce(root.<String>get("targetLabel"), "")), pattern);
                Predicate byDescription = cb.like(cb.lower(cb.coalesce(root.<String>get("description"), "")), pattern);
                predicates.add(cb.or(byActor, byTarget, byDescription));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public AuditLogResponse getLogById(Long id) {
        SystemLog systemLog = systemLogRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El registro de log no existe."));
        return toResponse(systemLog);
    }

    public AuditLogSummaryResponse getSummary() {
        long total = systemLogRepository.count();
        long info = systemLogRepository.countBySeverity(LogSeverity.INFO);
        long warning = systemLogRepository.countBySeverity(LogSeverity.WARNING);
        long error = systemLogRepository.countBySeverity(LogSeverity.ERROR);
        long auth = systemLogRepository.countByCategory(LogCategory.AUTH);
        long users = systemLogRepository.countByCategory(LogCategory.USER_MANAGEMENT);
        long evaluations = systemLogRepository.countByCategory(LogCategory.EVALUATION);
        long concepts = systemLogRepository.countByCategory(LogCategory.CONCEPT_CONTENT);

        return new AuditLogSummaryResponse(total, info, warning, error, auth, users, evaluations, concepts);
    }

    // =========================================================================
    // AUXILIARES
    // =========================================================================

    private SystemLog.SystemLogBuilder baseBuilder(LogEventType eventType, LogSeverity severity) {
        return SystemLog.builder()
                .eventType(eventType)
                .category(eventType.getDefaultCategory())
                .severity(severity)
                .createdAt(LocalDateTime.now(java.time.ZoneId.of("America/Lima")))
                .outcome("SUCCESS").correlationId(AuditContext.correlation());
    }

    private SessionPrincipal resolveAuthenticatedUser() {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication!=null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionPrincipal principal ? principal : null;
    }

    private AuditLogResponse toResponse(SystemLog systemLog) {
        return new AuditLogResponse(
                systemLog.getId(),
                systemLog.getEventType(),
                systemLog.getCategory(),
                systemLog.getSeverity(),
                systemLog.getActorUserId(),
                systemLog.getActorUsername(),
                systemLog.getActorRole(),
                systemLog.getTargetType(),
                systemLog.getTargetId(),
                systemLog.getTargetLabel(),
                systemLog.getAction(),
                systemLog.getDescription(),
                systemLog.getMetadata(),
                systemLog.getCreatedAt(), systemLog.getOutcome(), systemLog.getCorrelationId(), systemLog.getDeclaredIdentifier());
    }
}
