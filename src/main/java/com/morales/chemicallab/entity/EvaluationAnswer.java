package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Respuesta de un estudiante a una pregunta dentro de un {@link EvaluationAttempt}.
 * Para alternativa única basta con {@code selectedOption}. Los campos {@code correct}
 * y {@code pointsAwarded} se rellenan al enviar el intento con un cálculo básico
 * encapsulado; {@code answerText} queda disponible para futuros tipos de pregunta.
 */
@Entity
@Table(name = "evaluation_answers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private EvaluationAttempt attempt;

    @ManyToOne(optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private EvaluationQuestion question;

    @ManyToOne
    @JoinColumn(name = "selected_option_id")
    private EvaluationOption selectedOption;

    @Column(columnDefinition = "TEXT")
    private String answerText;

    // Resultado de la corrección. Null mientras el intento no se ha enviado.
    private Boolean correct;

    private Integer pointsAwarded;

    private LocalDateTime answeredAt;

    @PrePersist
    protected void onCreate() {
        answeredAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        answeredAt = LocalDateTime.now();
    }
}
