package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Sesión de pizarra interactiva en vivo. Es la unidad principal del módulo: la crea un
 * docente para un grado/sección, se usa en vivo (los trazos se sincronizan por
 * WebSocket/STOMP, no se persisten fila por fila) y al finalizar queda archivada con una
 * captura final.
 *
 * <p>Decisiones de almacenamiento (MVP):</p>
 * <ul>
 *   <li>La <strong>captura final</strong> se guarda como bytes en PostgreSQL
 *       ({@code bytea}), igual que {@link ConceptMaterial}, para no depender del sistema de
 *       archivos local. Los bytes son {@code LAZY} y nunca viajan en listados ni metadata.</li>
 *   <li>El permiso <strong>global</strong> de interacción vive aquí
 *       ({@link #interactionEnabled}); el permiso individual vive en
 *       {@link WhiteboardParticipant}.</li>
 *   <li>{@code CLOSED} es terminal: el servicio nunca reabre ni edita una sesión cerrada.</li>
 * </ul>
 */
@Entity
@Table(name = "whiteboard_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhiteboardSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private TeacherProfile teacher;

    @Column(nullable = false)
    private String grade;

    @Column(nullable = false)
    private String section;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WhiteboardSessionStatus status = WhiteboardSessionStatus.ACTIVE;

    // Permiso global de interacción de alumnos. Por defecto false: solo visualizan hasta
    // que el docente habilite la interacción.
    @Builder.Default
    @Column(nullable = false)
    private Boolean interactionEnabled = false;

    // Estado actual del lienzo de una sesión EN VIVO (trazos + textos) como JSON, para que un
    // estudiante que entra tarde o recarga reconstruya lo ya dibujado. Lo mantiene el frontend
    // docente de forma debounced; el backend solo lo guarda/devuelve (no interpreta su contenido)
    // y nunca lo registra en los logs de auditoría. La captura final (imagen) se mantiene aparte
    // para las sesiones CLOSED.
    @Column(name = "current_state_json", columnDefinition = "TEXT")
    private String currentStateJson;

    @Column(name = "state_updated_at")
    private LocalDateTime stateUpdatedAt;

    // --- Captura final (solo presente cuando la sesión está CLOSED) ---

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "final_snapshot_data")
    private byte[] finalSnapshotData;

    @Column(name = "final_snapshot_content_type", length = 100)
    private String finalSnapshotContentType;

    @Column(name = "final_snapshot_file_name", length = 255)
    private String finalSnapshotFileName;

    @Column(name = "final_snapshot_size")
    private Long finalSnapshotSize;

    // --- Marcas de tiempo del ciclo de vida ---

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime pausedAt;
    private LocalDateTime resumedAt;
    private LocalDateTime closedAt;

    // Nombre de usuario del docente que cerró la sesión (no es dato sensible).
    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (startedAt == null) {
            startedAt = createdAt;
        }
    }

    /** Indica si la sesión ya tiene una captura final almacenada. */
    public boolean hasSnapshot() {
        return finalSnapshotData != null && finalSnapshotData.length > 0;
    }
}
