package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Registro de trazabilidad de un evento importante del sistema (auditoría).
 *
 * <p>Guarda <strong>quién</strong> realizó la acción (actor), <strong>sobre qué</strong>
 * recurso (target), a qué <strong>módulo</strong> pertenece (category) y de qué
 * <strong>tipo</strong> de evento se trata (eventType), junto con una descripción legible.</p>
 *
 * <p>Por diseño nunca almacena datos sensibles: ni contraseñas, ni contraseñas
 * temporales, ni tokens JWT, ni respuestas completas de exámenes, ni stacktraces.</p>
 */
@Entity
@Table(name = "system_logs", indexes = {
        @Index(name = "idx_system_logs_created_at", columnList = "createdAt"),
        @Index(name = "idx_system_logs_category", columnList = "category"),
        @Index(name = "idx_system_logs_severity", columnList = "severity")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogSeverity severity;

    // Actor: usuario que realiza la acción. Puede ser nulo (por ejemplo, login fallido).
    private Long actorUserId;

    private String actorUsername;

    @Enumerated(EnumType.STRING)
    private Role actorRole;

    // Target: recurso afectado por la acción.
    private String targetType;

    private Long targetId;

    private String targetLabel;

    // Acción concreta realizada (verbo corto), útil para lectura rápida.
    private String action;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Opcionales de contexto técnico.
    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    // Información adicional en texto/JSON simple, sin datos sensibles.
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
