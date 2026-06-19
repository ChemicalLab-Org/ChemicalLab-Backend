package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Alternativa de una {@link EvaluationQuestion}. El campo {@code correct} indica si
 * es la opción válida; este dato es sensible y nunca se expone al estudiante antes
 * de enviar su intento (se omite en los DTO de estudiante).
 */
@Entity
@Table(name = "evaluation_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private EvaluationQuestion question;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String optionText;

    @Builder.Default
    @Column(nullable = false)
    private Boolean correct = false;

    @Builder.Default
    @Column(nullable = false)
    private Integer orderIndex = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}
