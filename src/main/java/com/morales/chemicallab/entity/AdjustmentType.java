package com.morales.chemicallab.entity;

/**
 * Tipo de un ajuste manual de calificación sobre un intento.
 * - BONUS: ajuste positivo que suma a la nota final (p. ej. +1 por desarrollo claro).
 * - PENALTY: ajuste negativo que resta a la nota final (p. ej. -0.5 por presentación
 *   incompleta).
 *
 * <p>El tipo se deriva del signo del monto al registrar el ajuste; no lo elige el cliente
 * de forma independiente para evitar incoherencias entre el signo y la etiqueta.</p>
 */
public enum AdjustmentType {
    BONUS,
    PENALTY
}
