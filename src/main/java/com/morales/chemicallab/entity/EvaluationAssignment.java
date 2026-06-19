package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Asignación de una evaluación a un grado y sección concretos. Replica el modelo de
 * {@link ConceptAssignment}: una misma evaluación puede compartirse con varias
 * secciones y el docente puede desactivar una asignación sin perder la evaluación.
 * Las fechas {@code startAt} y {@code dueAt} son opcionales y acotan la ventana de
 * disponibilidad para el estudiante.
 */
@Entity
@Table(name = "evaluation_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private TeacherProfile teacher;

    @Column(nullable = false)
    private String grade;

    @Column(nullable = false)
    private String section;

    private LocalDateTime startAt;

    private LocalDateTime dueAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    private LocalDateTime assignedAt;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }
}
