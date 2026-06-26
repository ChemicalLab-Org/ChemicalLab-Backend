package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Evaluación creada por un docente. Agrupa preguntas de alternativa única sobre un
 * tema de química y puede asignarse a varios grados/secciones mediante
 * {@link EvaluationAssignment}. Las preguntas viven en {@link EvaluationQuestion} y
 * los intentos de los estudiantes en {@link EvaluationAttempt}.
 */
@Entity
@Table(name = "evaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    private String topic;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvaluationStatus status = EvaluationStatus.DRAFT;

    // Número máximo de intentos permitidos por estudiante. Mínimo 1.
    @Builder.Default
    @Column(nullable = false)
    private Integer maxAttempts = 1;

    // Límite de tiempo en minutos. Opcional: null indica sin límite.
    private Integer timeLimitMinutes;

    // --- Configuración avanzada del intento (definida por el docente) ---

    // Si está activo, el estudiante puede usar la herramienta de apoyo químico
    // durante el intento. Por defecto false para preservar el comportamiento actual.
    @Builder.Default
    @Column(nullable = false)
    private Boolean allowChemicalCalculator = false;

    // Si está activo, el frontend reporta incidencias de pérdida de foco/salida de
    // pestaña y el backend las registra asociadas al intento. Por defecto false.
    @Builder.Default
    @Column(nullable = false)
    private Boolean trackTabExit = false;

    // Modo de presentación de las preguntas. Por defecto ALL_AT_ONCE para no alterar
    // el flujo histórico. No afecta la calificación ni las respuestas.
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionDisplayMode questionDisplayMode = QuestionDisplayMode.ALL_AT_ONCE;

    // Si está activo, el orden de las preguntas se aleatoriza por intento (el orden se
    // genera y se guarda al iniciar el intento, no cambia entre recargas). Por defecto
    // false para conservar el orden definido por el docente en evaluaciones existentes.
    @Builder.Default
    @Column(nullable = false)
    private Boolean randomizeQuestions = false;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by_teacher_id", nullable = false)
    private TeacherProfile createdByTeacher;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
