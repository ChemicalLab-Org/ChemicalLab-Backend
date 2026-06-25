package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Contenido conceptual de química creado por un docente. Reúne la explicación
 * teórica de un tema junto con sus pasos o secuencia, puntos clave, ejemplos y una
 * actividad sugerida. Un mismo contenido puede asignarse a varias secciones a través
 * de {@link ConceptAssignment}.
 *
 * <p>La categoría es texto libre: el docente puede usar las categorías químicas
 * clásicas (óxidos, ácidos, nomenclatura…) o registrar cualquier otro tema (enlace
 * químico, tabla periódica, clase introductoria, etc.). Los contenidos creados con
 * las categorías antiguas siguen siendo válidos, ya que se almacenaban como texto.</p>
 */
@Entity
@Table(name = "concept_contents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConceptContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    // Categoría temática como texto libre (máx. 100 caracteres). Compatible con las
    // categorías químicas antiguas, que ya se guardaban como texto en esta columna.
    @Column(nullable = false, length = 100)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String explanation;

    // Indicaciones o actividad sugerida para trabajar el tema en clase. Opcional.
    @Column(columnDefinition = "TEXT")
    private String suggestedActivity;

    // Listas simples de texto almacenadas en tablas auxiliares mediante @ElementCollection.
    // Se mantiene una solución sencilla y mantenible, sin librerías ni JSON adicionales.
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "concept_content_formation_steps", joinColumns = @JoinColumn(name = "concept_content_id"))
    @Column(name = "step", columnDefinition = "TEXT")
    @OrderColumn(name = "step_order")
    private List<String> formationSteps = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "concept_content_key_points", joinColumns = @JoinColumn(name = "concept_content_id"))
    @Column(name = "key_point", columnDefinition = "TEXT")
    @OrderColumn(name = "key_point_order")
    private List<String> keyPoints = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "concept_content_examples", joinColumns = @JoinColumn(name = "concept_content_id"))
    @Column(name = "example", columnDefinition = "TEXT")
    @OrderColumn(name = "example_order")
    private List<String> examples = new ArrayList<>();

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by_teacher_id", nullable = false)
    private TeacherProfile createdByTeacher;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConceptStatus status = ConceptStatus.DRAFT;

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
