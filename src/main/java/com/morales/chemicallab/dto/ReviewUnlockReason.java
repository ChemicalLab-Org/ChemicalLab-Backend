package com.morales.chemicallab.dto;

/**
 * Motivo por el que la revisión detallada de una evaluación está (o no) disponible para
 * las estudiantes. La revisión se bloquea mientras el grupo no haya cerrado, para que
 * una alumna que termina antes no pueda ver las respuestas correctas y filtrarlas.
 *
 * <ul>
 *   <li>{@link #DEADLINE_REACHED}: la revisión está disponible porque ya venció el plazo
 *       de la evaluación (fecha límite de la asignación).</li>
 *   <li>{@link #ALL_ASSIGNED_STUDENTS_FINISHED}: nombre histórico conservado para el
 *       cierre administrativo (archivo o desactivación de todas las asignaciones).
 *       Agotar intentos del padrón actual ya no desbloquea la revisión.</li>
 *   <li>{@link #LOCKED_WAITING_FOR_GROUP}: la revisión sigue bloqueada; existe una fecha
 *       límite futura de una asignación, aunque el padrón actual ya haya finalizado.</li>
 *   <li>{@link #NO_DEADLINE}: la revisión sigue bloqueada y la evaluación no tiene fecha
 *       límite para todas sus asignaciones; requiere cierre administrativo.</li>
 * </ul>
 */
public enum ReviewUnlockReason {
    DEADLINE_REACHED,
    ALL_ASSIGNED_STUDENTS_FINISHED,
    LOCKED_WAITING_FOR_GROUP,
    NO_DEADLINE
}
