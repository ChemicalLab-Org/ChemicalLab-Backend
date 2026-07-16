package com.morales.chemicallab.dto;

/**
 * Motivo por el que la revisión detallada de una evaluación está o no disponible para
 * las estudiantes. La revisión se desbloquea cuando la evaluación cierra para todo el
 * grupo (todas finalizaron) o cuando vence la fecha límite; mientras tanto queda
 * bloqueada para no filtrar respuestas correctas entre compañeras.
 *
 * <ul>
 *   <li>{@code DEADLINE_REACHED}: disponible porque ya se cumplió la fecha límite.</li>
 *   <li>{@code ALL_ASSIGNED_STUDENTS_FINISHED}: disponible porque todas las estudiantes
 *       asignadas ya finalizaron sus intentos (o la evaluación está archivada).</li>
 *   <li>{@code LOCKED_WAITING_FOR_GROUP}: bloqueada; existe fecha límite futura y aún
 *       quedan estudiantes por finalizar.</li>
 *   <li>{@code NO_DEADLINE}: bloqueada; la evaluación no tiene fecha límite, así que solo
 *       se desbloqueará cuando todas las estudiantes asignadas finalicen.</li>
 * </ul>
 */
public enum ReviewUnlockReason {
    DEADLINE_REACHED,
    ALL_ASSIGNED_STUDENTS_FINISHED,
    LOCKED_WAITING_FOR_GROUP,
    NO_DEADLINE
}
