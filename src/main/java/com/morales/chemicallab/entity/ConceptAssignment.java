package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Asignación de un contenido conceptual a un grado y sección concretos. Permite
 * que un mismo {@link ConceptContent} se comparta con varias secciones y que el
 * docente desactive una asignación sin perder el contenido.
 */
@Entity
@Table(name = "concept_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConceptAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "concept_content_id", nullable = false)
    private ConceptContent conceptContent;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private TeacherProfile teacher;

    @Column(nullable = false)
    private String grade;

    @Column(nullable = false)
    private String section;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    private LocalDateTime assignedAt;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }
}
