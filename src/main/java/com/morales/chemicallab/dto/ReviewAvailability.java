package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Resultado de evaluar la regla centralizada de disponibilidad de revisión para las
 * estudiantes de una evaluación:
 *
 * <pre>reviewAvailable = deadlineReached OR allAssignedStudentsFinished</pre>
 *
 * Se calcula a nivel de evaluación (no de estudiante individual) para evitar que quien
 * termina antes pueda ver las respuestas correctas y filtrarlas a sus compañeras. Los
 * conteos ({@code assignedStudentsCount}, {@code finishedStudentsCount},
 * {@code pendingStudentsCount}) permiten al frontend mostrar el progreso general del
 * grupo sin revelar nombres ni datos personales de las estudiantes pendientes.
 *
 * <p>{@code deadlineAt} es la fecha límite a partir de la cual la revisión se desbloquea
 * automáticamente; es {@code null} cuando la evaluación no define fecha límite.</p>
 */
public record ReviewAvailability(
        boolean reviewAvailable,
        ReviewUnlockReason reason,
        int assignedStudentsCount,
        int finishedStudentsCount,
        int pendingStudentsCount,
        LocalDateTime deadlineAt
) {}
