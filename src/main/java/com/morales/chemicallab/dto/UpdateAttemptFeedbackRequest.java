package com.morales.chemicallab.dto;

import jakarta.validation.constraints.Size;

/**
 * Retroalimentación general que el docente escribe para el estudiante sobre un intento.
 * Es opcional (puede enviarse vacía para borrarla) y tiene un límite de longitud
 * razonable. Solo se le muestra al estudiante cuando la calificación está cerrada.
 */
public record UpdateAttemptFeedbackRequest(

        @Size(max = 1500, message = "La retroalimentación no puede superar 1500 caracteres")
        String overallFeedback

) {}
