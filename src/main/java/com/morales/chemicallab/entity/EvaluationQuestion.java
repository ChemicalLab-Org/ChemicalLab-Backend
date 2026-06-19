package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Pregunta de una evaluación. Para este MVP es de alternativa única
 * ({@link QuestionType#MULTIPLE_CHOICE}): sus {@link EvaluationOption} ofrecen las
 * alternativas y exactamente una debe estar marcada como correcta para poder
 * publicar la evaluación.
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
