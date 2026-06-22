package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Intento de un estudiante sobre una evaluación. Registra el orden del intento, su
 * estado y, una vez enviado, el puntaje calculado. Como las preguntas son de
 * alternativa única, la calificación automática se ejecuta al enviar: el intento
 * queda en {@link AttemptStatus#GRADED} con su {@code score}, {@code maxScore} y
 * {@code gradedAt}.
 */
@Entity
@Table(name = "evaluation_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    // Asignación bajo la cual se inició el intento. Permite acotar por ventana de fechas.
    @ManyToOne
    @JoinColumn(name = "assignment_id")
    private EvaluationAssignment assignment;

    @ManyToOne(optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private StudentProfile student;

    @Builder.Default
    @Column(nullable = false)
    private Integer attemptNumber = 1;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    private LocalDateTime startedAt;

    private LocalDateTime submittedAt;

    // Fecha en que el intento quedó calificado. Para evaluaciones de alternativa única
    // coincide con el envío, porque la calificación automática se ejecuta al enviar.
    private LocalDateTime gradedAt;

    // Puntaje obtenido y puntaje máximo. Se calculan al enviar el intento.
    private Integer score;

    private Integer maxScore;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
