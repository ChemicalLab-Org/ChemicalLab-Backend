package com.morales.chemicallab.entity;

/**
 * Modo en que se le muestran las preguntas al estudiante durante el intento.
 * - ALL_AT_ONCE: todas las preguntas en una sola pantalla (flujo histórico).
 * - ONE_BY_ONE: una pregunta por pantalla, con navegación anterior/siguiente.
 *
 * <p>Es solo una preferencia de presentación: no altera la calificación, las
 * respuestas ni qué información recibe el estudiante.</p>
 */
public enum QuestionDisplayMode {
    ALL_AT_ONCE,
    ONE_BY_ONE
}
