package com.morales.chemicallab.entity;

/**
 * Tipo de pregunta de una evaluación.
 *
 * <p>Para este MVP solo se admite alternativa única ({@code MULTIPLE_CHOICE}): la
 * pregunta ofrece varias alternativas y exactamente una es la correcta. Un caso
 * verdadero/falso se modela igualmente con dos alternativas. Tipos más complejos
 * (preguntas abiertas extensas) quedan fuera de alcance por ahora.</p>
 */
public enum QuestionType {
    MULTIPLE_CHOICE
}
