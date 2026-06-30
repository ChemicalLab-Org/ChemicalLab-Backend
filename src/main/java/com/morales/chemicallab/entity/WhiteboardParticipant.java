package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Estudiante unido a una {@link WhiteboardSession} y su permiso individual de interacción.
 *
 * <p>El permiso efectivo de dibujo se calcula combinando {@link #interactionOverride} con el
 * permiso global de la sesión: {@code ALLOWED} y {@code BLOCKED} fuerzan el resultado;
 * {@code FOLLOW_GLOBAL} delega en {@code session.interactionEnabled}.</p>
 *
 * <p>Un estudiante tiene como máximo un participante por sesión (restricción única
 * {@code session_id + student_id}), de modo que volver a unirse no duplica filas.</p>
 */
@Entity
@Table(name = "whiteboard_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_whiteboard_participant_session_student",
                columnNames = {"session_id", "student_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhiteboardParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private WhiteboardSession session;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private StudentProfile student;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_override", nullable = false, length = 20)
    private WhiteboardInteractionOverride interactionOverride = WhiteboardInteractionOverride.FOLLOW_GLOBAL;

    private LocalDateTime joinedAt;
    private LocalDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (joinedAt == null) {
            joinedAt = now;
        }
        lastSeenAt = now;
    }
}
