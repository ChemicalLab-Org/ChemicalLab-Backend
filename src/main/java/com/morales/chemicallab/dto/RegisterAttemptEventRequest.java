package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo para registrar una incidencia de foco durante un intento (salida/retorno de
 * pestaña o ventana). El intento se toma de la ruta y se valida que pertenezca al
 * estudiante autenticado. La descripción es opcional y debe ser breve y no sensible.
 */
public record RegisterAttemptEventRequest(

        @NotNull(message = "El tipo de evento es obligatorio")
        AttemptEventType eventType,

        @Size(max = 200, message = "La descripción no puede superar 200 caracteres")
        String description

) {}
