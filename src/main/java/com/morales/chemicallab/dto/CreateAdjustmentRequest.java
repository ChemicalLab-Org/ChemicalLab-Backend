package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Ajuste manual de puntaje que el docente aplica al intento completo. El monto va en la
 * escala de la nota final (0–20): positivo para una bonificación, negativo para una
 * penalización. El motivo es obligatorio para que ningún ajuste quede sin justificación;
 * la nota final resultante se acota al rango válido en el servicio.
 */
public record CreateAdjustmentRequest(

        @NotNull(message = "El monto del ajuste es obligatorio")
        @DecimalMin(value = "-20.0", message = "El monto del ajuste no puede ser menor a -20")
        @DecimalMax(value = "20.0", message = "El monto del ajuste no puede ser mayor a 20")
        BigDecimal amount,

        @NotBlank(message = "El motivo del ajuste es obligatorio")
        @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
        String reason

) {}
