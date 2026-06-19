package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Intento de un estudiante sobre una evaluación. Registra el orden del intento, su
 * estado y, una vez enviado, el puntaje calculado. La calificación definitiva y la
 * vista de resultados se abordan en una sesión posterior; aquí se deja la estructura
 * lista ({@code score}, {@code maxScore}, estado {@link AttemptStatus#GRADED}).
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
