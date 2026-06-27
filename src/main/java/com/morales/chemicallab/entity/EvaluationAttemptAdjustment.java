package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ajuste manual de puntaje que el docente aplica a un {@link EvaluationAttempt} completo,
 * por encima del puntaje calculado por pregunta. Permite sumar o restar puntos a la nota
 * final con una justificación obligatoria (p. ej. +1 por desarrollo claro, -0.5 por
 * presentación incompleta).
 *
 * <p>Cada ajuste deja constancia de quién lo creó y cuándo, y nunca se sobrescribe la nota
 * en silencio: la nota final del intento se recompone como la nota base (0–20) más la suma
 * de los ajustes activos, acotada al rango válido. La anulación es lógica
 * ({@code active = false}) para conservar la trazabilidad.</p>
 */
@Entity
@Table(name = "evaluation_attempt_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAttemptAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private EvaluationAttempt attempt;

    // Monto del ajuste en la escala de la nota final (0–20). Positivo para una bonificación,
    // negativo para una penalización. Nunca es cero (un ajuste sin efecto no tiene sentido).
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal amount;

    // Tipo derivado del signo del monto. Se guarda para mostrarlo y filtrar sin recalcular.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdjustmentType type;

    // Motivo obligatorio del ajuste. Es información docente (no se expone como criterio
    // interno al estudiante salvo en el resumen permitido).
    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    // Docente que registró el ajuste.
    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private TeacherProfile createdBy;

    private LocalDateTime createdAt;

    // Anulación lógica: un ajuste anulado deja de afectar la nota pero permanece registrado.
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
