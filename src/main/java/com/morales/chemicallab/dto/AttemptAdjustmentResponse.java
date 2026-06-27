package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AdjustmentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ajuste manual de puntaje aplicado a un intento, tal como lo ve el docente: el monto en
 * escala 0–20 (con su signo), el tipo derivado del signo, el motivo y la autoría. No se
 * expone al estudiante con este nivel de detalle.
 */
public record AttemptAdjustmentResponse(
        Long id,
        BigDecimal amount,
        AdjustmentType type,
        String reason,
        String createdByName,
        LocalDateTime createdAt
) {}
