package com.morales.chemicallab.entity;

/**
 * Tipo de pregunta de una evaluación.
 *
 * <p>{@code MULTIPLE_CHOICE}: alternativa única; la pregunta ofrece varias
 * alternativas y exactamente una es la correcta (un verdadero/falso se modela con dos
 * alternativas). Se califica automáticamente al enviar el intento.</p>
 *
 * <p>{@code OPEN_TEXT}: pregunta abierta; el estudiante responde con texto libre. No
 * tiene alternativas ni clave de respuesta automática: requiere revisión manual del
 * docente, que asigna el puntaje (entre 0 y el máximo de la pregunta) y opcionalmente
 * deja retroalimentación. Puede tener una respuesta esperada o criterio de corrección
 * visible solo para el docente.</p>
 */
public enum QuestionType {
    MULTIPLE_CHOICE,
    OPEN_TEXT
}
