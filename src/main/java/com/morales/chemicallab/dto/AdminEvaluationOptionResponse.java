package com.morales.chemicallab.dto;

/**
 * Alternativa vista por el administrador en la supervisión de una evaluación.
 *
 * <p>De forma deliberada <strong>no</strong> incluye el campo {@code correct}: la
 * supervisión institucional del administrador es de solo lectura y no debe exponer
 * la clave de respuestas. El tipo garantiza en compilación que la alternativa
 * correcta nunca viaje en esta vista.</p>
 */
public record AdminEvaluationOptionResponse(
        Long id,
        String optionText,
        Integer orderIndex
) {}
