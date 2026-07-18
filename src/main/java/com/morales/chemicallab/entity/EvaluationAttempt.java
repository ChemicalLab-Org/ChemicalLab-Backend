package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
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

    // Orden de las preguntas para este intento, como lista de IDs separada por comas. Se
    // fija al iniciar el intento (aleatorio si la evaluación lo indica, o el orden del
    // docente) y se reutiliza siempre: no se regenera al consultar ni al recargar.
    @Column(columnDefinition = "TEXT")
    private String questionOrder;

    // Solo para el modo ONE_BY_ONE: índice (0-based) de la pregunta actual dentro de
    // questionOrder. Las preguntas con índice menor quedan bloqueadas (sin retroceso).
    @Builder.Default
    @Column(nullable = false)
    private Integer currentQuestionIndex = 0;

    // Fecha en que el intento quedó calificado. Para evaluaciones de alternativa única
    // coincide con el envío, porque la calificación automática se ejecuta al enviar.
    private LocalDateTime gradedAt;

    // Puntaje obtenido y puntaje máximo. Se calculan al enviar el intento.
    private Integer score;

    private Integer maxScore;

    // Nota final del intento en escala 0–20: se obtiene de la nota base (score/maxScore*20)
    // más la suma de los ajustes manuales activos, acotada a [0, 20]. Se recalcula tras
    // cada revisión, ajuste o cierre. Es la nota que ve el estudiante una vez cerrada la
    // calificación.
    @Column(precision = 5, scale = 2)
    private BigDecimal finalScore;

    // Retroalimentación general del docente para el estudiante. Solo se le muestra cuando la
    // calificación está cerrada.
    @Column(columnDefinition = "TEXT")
    private String overallFeedback;

    // Indica si la calificación del intento ya fue cerrada por el docente. Mientras sea
    // false, el docente puede seguir ajustando puntajes y ajustes, y el estudiante ve el
    // intento como "pendiente de revisión" (sin nota final). Al cerrarse, la nota final y la
    // retroalimentación quedan visibles para el estudiante y se bloquea la edición.
    // columnDefinition con default true para que ddl-auto=update agregue la columna a las
    // tablas con datos previos dejando los intentos antiguos como ya cerrados (su resultado
    // ya era visible). Los intentos nuevos fijan el valor explícitamente al enviarse.
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean gradeClosed = false;

    private LocalDateTime gradeClosedAt;

    // Docente que cerró la calificación. Queda null en los intentos cerrados de forma
    // automática (solo alternativa única, sin revisión manual).
    @ManyToOne
    @JoinColumn(name = "grade_closed_by")
    private TeacherProfile gradeClosedBy;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
