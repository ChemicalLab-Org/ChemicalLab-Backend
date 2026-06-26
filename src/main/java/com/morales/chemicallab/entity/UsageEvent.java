package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Evento de uso e interacción dentro de ChemicalLab.
 *
 * <p>Mide <strong>cómo se usa</strong> la plataforma con fines educativos y de producto:
 * acceso a módulos, uso de la tabla periódica, formación de compuestos, apertura de
 * contenidos y evaluaciones, clics relevantes, etc. Se almacena en una tabla
 * <strong>independiente</strong> de los logs de auditoría ({@link SystemLog}).</p>
 *
 * <p><strong>Diferencia con los logs de auditoría:</strong> los logs registran eventos de
 * seguridad y trazabilidad administrativa (quién hizo qué acción crítica: login, creación
 * de usuarios, reset de contraseñas, publicación de evaluaciones). Las métricas de uso
 * registran la interacción educativa del usuario con los módulos.</p>
 *
 * <p>Por diseño nunca almacena datos sensibles: ni contraseñas, ni contraseñas temporales,
 * ni tokens, ni respuestas de evaluación, ni claves de respuesta, ni payloads completos.
 * El usuario y el rol se resuelven del contexto de seguridad, nunca de datos enviados por
 * el cliente. La metadata es corta y se sanitiza antes de guardarse.</p>
 */
@Entity
@Table(name = "usage_events", indexes = {
        @Index(name = "idx_usage_events_occurred_at", columnList = "occurredAt"),
        @Index(name = "idx_usage_events_module", columnList = "module"),
        @Index(name = "idx_usage_events_event_type", columnList = "eventType"),
        @Index(name = "idx_usage_events_user_role", columnList = "userRole")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Usuario autenticado que generó el evento. Se resuelve del contexto de seguridad,
    // nunca de datos enviados por el frontend.
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role userRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageModule module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageEventType eventType;

    // Recurso involucrado (opcional). Por ejemplo: "EVALUATION", "CONCEPT", "ELEMENT".
    private String resourceType;

    // Identificador del recurso (opcional). String para admitir ids no numéricos
    // (p. ej. el símbolo de un elemento). Nunca contiene datos sensibles.
    private String resourceId;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Metadata corta y segura en formato "clave=valor; clave2=valor2". Se sanitiza antes
    // de guardar: nunca contraseñas, tokens, respuestas ni claves de evaluación.
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
