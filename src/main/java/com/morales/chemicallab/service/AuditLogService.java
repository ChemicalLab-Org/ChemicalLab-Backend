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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Servicio central de trazabilidad. Registra eventos importantes del sistema y permite
 * consultarlos desde el panel administrativo.
 *
 * <p>Principios de diseño:</p>
 * <ul>
 *   <li><strong>No rompe la operación principal:</strong> el guardado ocurre en una
 *       transacción aparte ({@link AuditLogWriter}) y cualquier error se captura aquí, de
 *       modo que un fallo de logging nunca impide iniciar sesión, crear un usuario o
 *       publicar una evaluación.</li>
 *   <li><strong>No guarda datos sensibles:</strong> nunca se registran contraseñas,
 *       contraseñas temporales, tokens ni respuestas completas de exámenes.</li>
 *   <li><strong>Actor automático:</strong> para eventos de usuarios autenticados el actor
 *       se resuelve del contexto de seguridad; el login tiene métodos específicos.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

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

    /**
     * Registra un evento resolviendo el actor a partir del usuario autenticado. La
     * categoría se deriva del tipo de evento. El guardado se delega al escritor en una
     * transacción aparte y cualquier fallo se captura para no afectar la operación origen.
     */
    public void recordEvent(LogEventType eventType, LogSeverity severity, String targetType,
                            Long targetId, String targetLabel, String action,
                            String description, String metadata) {
        UserAccount actor = resolveAuthenticatedUser();
        SystemLog systemLog = baseBuilder(eventType, severity)
                .actorUserId(actor != null ? actor.getId() : null)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorRole(actor != null ? actor.getRole() : null)
                .targetType(targetType)
                .targetId(targetId)
                .targetLabel(targetLabel)
                .action(action)
                .description(description)
                .metadata(metadata)
                .build();
        safePersist(systemLog);
    }

    /** Registra un inicio de sesión exitoso con el usuario autenticado como actor. */
    public void recordLoginSuccess(UserAccount user) {
        SystemLog systemLog = baseBuilder(LogEventType.LOGIN_SUCCESS, LogSeverity.INFO)
                .actorUserId(user != null ? user.getId() : null)
                .actorUsername(user != null ? user.getUsername() : null)
                .actorRole(user != null ? user.getRole() : null)
                .action("Inicio de sesión")
                .description("Inicio de sesión correcto.")
                .build();
        safePersist(systemLog);
    }

    /**
     * Registra un intento de inicio de sesión fallido. El actor queda sin identificar
     * (no hay sesión válida); solo se guarda el usuario o correo intentado y el motivo.
     * Nunca se almacena la contraseña ingresada.
     */
    public void recordLoginFailed(String attemptedUsername, String reason) {
        SystemLog systemLog = baseBuilder(LogEventType.LOGIN_FAILED, LogSeverity.WARNING)
                .actorUsername(attemptedUsername)
                .action("Inicio de sesión fallido")
                .description(reason)
                .build();
        safePersist(systemLog);
    }

    // =========================================================================
    // CONSULTA (panel administrativo)
    // =========================================================================

    public Page<AuditLogResponse> listLogs(LogCategory category, LogEventType eventType,
                                           LogSeverity severity, Role actorRole, String search,
                                           LocalDateTime from, LocalDateTime to, Pageable pageable) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;
        return systemLogRepository
                .search(category, eventType, severity, actorRole, normalizedSearch, from, to, pageable)
                .map(this::toResponse);
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
                .createdAt(LocalDateTime.now());
    }

    /** Guarda el log sin propagar errores: la trazabilidad no debe romper la operación. */
    private void safePersist(SystemLog systemLog) {
        try {
            auditLogWriter.persist(systemLog);
        } catch (Exception ex) {
            // No relanzamos: un fallo de logging no debe afectar la operación principal.
            log.warn("No fue posible registrar el evento de trazabilidad {}: {}",
                    systemLog.getEventType(), ex.getMessage());
        }
    }

    private UserAccount resolveAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return userAccountRepository.findByUsername(authentication.getName()).orElse(null);
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
                systemLog.getCreatedAt());
    }
}
