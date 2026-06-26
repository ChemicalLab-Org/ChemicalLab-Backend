package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Incidencia de foco registrada durante un intento de evaluación (salida/retorno de
 * pestaña o ventana). Se registra solo cuando la evaluación tiene activada la detección
 * de salida de pestaña ({@code trackTabExit}) y siempre asociada al intento del propio
 * estudiante.
 *
 * <p>Por diseño guarda lo mínimo: el intento, el tipo de evento, el momento y una
 * descripción corta y segura. Nunca almacena contenido de otras pestañas, capturas de
 * pantalla, historial del navegador ni datos sensibles.</p>
 *
 * <p>Es trazabilidad a nivel de intento, no un log global de auditoría: vive en su
 * propia tabla para no saturar el visor de logs administrativos.</p>
 */
@Entity
@Table(name = "evaluation_attempt_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAttemptEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private EvaluationAttempt attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptEventType eventType;

    @Column(length = 200)
    private String description;

    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
