package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Pregunta de una evaluación. Puede ser de alternativa única
 * ({@link QuestionType#MULTIPLE_CHOICE}) —sus {@link EvaluationOption} ofrecen las
 * alternativas y exactamente una debe estar marcada como correcta para poder publicar—
 * o de respuesta abierta ({@link QuestionType#OPEN_TEXT}), que no tiene alternativas: el
 * estudiante responde con texto y el docente la califica manualmente. En las preguntas
 * abiertas, {@code expectedAnswer} guarda una respuesta esperada o criterio de
 * corrección visible solo para el docente (nunca se expone al estudiante).
 */
@Entity
@Table(name = "evaluation_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType = QuestionType.MULTIPLE_CHOICE;

    @Builder.Default
    @Column(nullable = false)
    private Integer points = 1;

    @Builder.Default
    @Column(nullable = false)
    private Integer orderIndex = 0;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    // Solo para preguntas abiertas (OPEN_TEXT): respuesta esperada o criterio de
    // corrección que orienta la revisión manual. Es información sensible visible solo
    // para el docente; nunca se incluye en los DTO del estudiante.
    @Column(columnDefinition = "TEXT")
    private String expectedAnswer;

    // Indica si la pregunta es obligatoria. Para preguntas abiertas obligatorias el
    // estudiante no puede enviar el intento con la respuesta en blanco. Por defecto true
    // para no alterar el comportamiento de las preguntas ya existentes.
    // columnDefinition con default para que ddl-auto=update pueda agregar la columna a
    // tablas con datos previos (las preguntas existentes quedan como obligatorias).
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean required = true;

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
