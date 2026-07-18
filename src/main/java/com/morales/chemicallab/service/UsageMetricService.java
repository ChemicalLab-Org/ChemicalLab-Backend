package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.RecordUsageEventRequest;
import com.morales.chemicallab.dto.UsageCountResponse;
import com.morales.chemicallab.dto.UsageEventResponse;
import com.morales.chemicallab.dto.UsageMetricsSummaryResponse;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UsageEventRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de métricas de usabilidad e interacción.
 *
 * <p>Registra <strong>cómo se usa</strong> la plataforma (acceso a módulos, uso de la tabla
 * periódica, formación de compuestos, apertura de contenidos/evaluaciones, clics relevantes)
 * y expone un resumen para el panel administrativo. Está deliberadamente separado de
 * {@link AuditLogService}, que registra eventos de seguridad y trazabilidad.</p>
 *
 * <p>Principios:</p>
 * <ul>
 *   <li><strong>Actor de confianza:</strong> el usuario y el rol se resuelven del contexto de
 *       seguridad (username del token), nunca de datos enviados por el cliente.</li>
 *   <li><strong>Sin datos sensibles:</strong> la metadata se sanitiza con
 *       {@link UsageMetadataSanitizer} antes de guardarse; nunca se almacenan contraseñas,
 *       tokens, respuestas ni claves de evaluación.</li>
 *   <li><strong>Consulta solo para administradores:</strong> el resumen y los eventos
 *       recientes se exponen únicamente en endpoints protegidos para ADMINISTRADOR.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageMetricService {

    private static final int MAX_RECENT = 100;

    private final UsageEventRepository usageEventRepository;
    private final UserAccountRepository userAccountRepository;

    // =========================================================================
    // REGISTRO
    // =========================================================================

    /**
     * Registra un evento de uso a nombre del usuario autenticado. El {@code username}
     * proviene del token (resuelto en el controlador); el rol y el id se toman de la cuenta,
     * no de la petición. La metadata se sanitiza antes de almacenarse.
     *
     * <p>Se ejecuta en una transacción <strong>independiente</strong> ({@code REQUIRES_NEW}):
     * la métrica es de mejor esfuerzo y nunca debe afectar a la operación que la dispara. Así, si
     * el guardado falla (p. ej. por una restricción de la BD), solo se revierte esta transacción y
     * el llamador puede capturar la excepción sin que su propia transacción quede marcada para
     * rollback (lo que provocaría un 500 al hacer commit aunque su lógica fuera correcta).</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvent(String username, RecordUsageEventRequest request) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("El usuario autenticado no existe."));

        UsageEvent event = UsageEvent.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .userRole(user.getRole())
                .module(request.module())
                .eventType(request.eventType())
                .resourceType(blankToNull(request.resourceType()))
                .resourceId(blankToNull(request.resourceId()))
                .description(blankToNull(request.description()))
                .metadata(UsageMetadataSanitizer.sanitize(request.metadata()))
                .occurredAt(LocalDateTime.now())
                .build();

        usageEventRepository.save(event);
    }

    // =========================================================================
    // CONSULTA (panel administrativo)
    // =========================================================================

    /**
     * Resumen agregado por módulo, tipo de evento y rol, más el total general. El rango de
     * fechas es opcional; cada conteo se obtiene con una {@link Specification} dinámica para
     * no enviar parámetros nulos sin tipo a PostgreSQL.
     */
    public UsageMetricsSummaryResponse getSummary(LocalDateTime from, LocalDateTime to) {
        long total = usageEventRepository.count(buildSpecification(null, null, null, from, to));

        List<UsageCountResponse> byModule = new ArrayList<>();
        for (UsageModule module : UsageModule.values()) {
            long count = usageEventRepository.count(buildSpecification(module, null, null, from, to));
            if (count > 0) {
                byModule.add(new UsageCountResponse(module.name(), count));
            }
        }
        byModule.sort((a, b) -> Long.compare(b.count(), a.count()));

        List<UsageCountResponse> byEventType = new ArrayList<>();
        for (UsageEventType type : UsageEventType.values()) {
            long count = usageEventRepository.count(buildSpecification(null, type, null, from, to));
            if (count > 0) {
                byEventType.add(new UsageCountResponse(type.name(), count));
            }
        }
        byEventType.sort((a, b) -> Long.compare(b.count(), a.count()));

        List<UsageCountResponse> byRole = new ArrayList<>();
        for (Role role : Role.values()) {
            long count = usageEventRepository.count(buildSpecification(null, null, role, from, to));
            if (count > 0) {
                byRole.add(new UsageCountResponse(role.name(), count));
            }
        }
        byRole.sort((a, b) -> Long.compare(b.count(), a.count()));

        return new UsageMetricsSummaryResponse(total, byModule, byEventType, byRole);
    }

    /**
     * Últimos eventos de uso (más recientes primero), con filtros opcionales por módulo,
     * tipo de interacción y rol. Devuelve siempre un DTO seguro sin datos sensibles.
     */
    public List<UsageEventResponse> getRecentEvents(int limit, UsageModule module,
                                                    UsageEventType eventType, Role role) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_RECENT);
        Specification<UsageEvent> spec = buildSpecification(module, eventType, role, null, null);
        PageRequest pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return usageEventRepository.findAll(spec, pageable).map(this::toResponse).getContent();
    }

    /** Desglose por módulo (sin tipo de evento ni rol), respetando el rango de fechas. */
    public List<UsageCountResponse> countByModule(LocalDateTime from, LocalDateTime to) {
        return getSummary(from, to).byModule();
    }

    /** Desglose por rol, respetando el rango de fechas. */
    public List<UsageCountResponse> countByRole(LocalDateTime from, LocalDateTime to) {
        return getSummary(from, to).byRole();
    }

    // =========================================================================
    // AUXILIARES
    // =========================================================================

    /**
     * Construye el filtro de forma dinámica: cada criterio se agrega como predicado solo
     * cuando tiene valor, de modo que nunca se envían binds nulos sin tipo.
     */
    private Specification<UsageEvent> buildSpecification(UsageModule module, UsageEventType eventType,
                                                         Role role, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (module != null) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (eventType != null) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("userRole"), role));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private UsageEventResponse toResponse(UsageEvent event) {
        return new UsageEventResponse(
                event.getId(),
                event.getUserId(),
                event.getUsername(),
                event.getUserRole(),
                event.getModule(),
                event.getEventType(),
                event.getResourceType(),
                event.getResourceId(),
                event.getDescription(),
                event.getMetadata(),
                event.getOccurredAt());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
