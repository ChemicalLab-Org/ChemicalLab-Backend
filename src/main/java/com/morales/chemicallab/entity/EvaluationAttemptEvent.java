package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Evento de trazabilidad registrado durante un intento de evaluación: incidencias de
 * foco (salida/retorno de pestaña o ventana, solo si la evaluación activa
 * {@code trackTabExit}), hitos del ciclo de vida (inicio, envío, salida) y uso de
 * herramientas permitidas. Siempre asociado al intento del propio estudiante.
 *
 * <p>Por diseño guarda lo mínimo: el intento, el tipo de evento, el momento, una
 * descripción corta y, como mucho, una {@code metadata} segura y acotada (p. ej.
 * {@code tool=PERIODIC_TABLE} o {@code source=VISIBILITY_CHANGE}). Nunca almacena
 * respuestas, claves, contenido de otras pestañas, capturas de pantalla, historial del
 * navegador, tokens ni datos sensibles.</p>
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

    // Metadata segura y acotada del evento (p. ej. "tool=PERIODIC_TABLE" o
    // "source=VISIBILITY_CHANGE"). Solo claves/valores controlados; nunca datos sensibles.
    @Column(length = 255)
    private String metadata;

    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
