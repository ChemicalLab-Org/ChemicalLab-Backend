package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

/**
 * Calificación manual que el docente asigna a una respuesta abierta. El puntaje debe
 * estar entre 0 y el puntaje máximo de la pregunta (el límite superior se valida en el
 * servicio, donde se conoce la pregunta). La retroalimentación es opcional.
 */
public record ManualGradeRequest(

        @NotNull(message = "El puntaje es obligatorio")
        @Min(value = 0, message = "El puntaje no puede ser negativo")
        Integer score,

        @Size(max = 2000, message = "La retroalimentación no puede superar 2000 caracteres")
        String feedback

) {}
