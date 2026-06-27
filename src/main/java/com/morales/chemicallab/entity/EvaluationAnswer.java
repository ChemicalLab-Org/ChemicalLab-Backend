package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Respuesta de un estudiante a una pregunta dentro de un {@link EvaluationAttempt}.
 * Para alternativa única basta con {@code selectedOption}, y {@code correct}/
 * {@code pointsAwarded} se rellenan automáticamente al enviar el intento. Para
 * preguntas abiertas el estudiante escribe en {@code answerText} y el docente la
 * califica manualmente: {@code reviewed} marca que ya fue revisada, {@code pointsAwarded}
 * guarda el puntaje asignado, {@code teacherFeedback} la retroalimentación opcional y
 * {@code reviewedAt}/{@code reviewedBy} el momento y autor de la revisión.
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

    // Resultado de la corrección. En alternativa única se rellena al enviar; en preguntas
    // abiertas queda null (no hay clave automática).
    private Boolean correct;

    private Integer pointsAwarded;

    // Revisión manual de preguntas abiertas. En alternativa única queda siempre true (la
    // corrección es automática); en preguntas abiertas pasa a true cuando el docente la
    // califica. Mientras sea false, el puntaje de esta respuesta no es definitivo.
    // columnDefinition con default para que ddl-auto=update pueda agregar la columna a
    // tablas con datos previos (las respuestas existentes quedan como ya revisadas, pues
    // corresponden a alternativa única con corrección automática).
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean reviewed = true;

    // Retroalimentación opcional del docente para una respuesta abierta.
    @Column(columnDefinition = "TEXT")
    private String teacherFeedback;

    private LocalDateTime reviewedAt;

    // Docente que realizó la revisión manual (null mientras no se haya revisado).
    @ManyToOne
    @JoinColumn(name = "reviewed_by")
    private TeacherProfile reviewedBy;

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
