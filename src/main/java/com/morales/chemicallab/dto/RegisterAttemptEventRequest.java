package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptEventType;
import com.morales.chemicallab.entity.AttemptTool;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo para registrar un evento de trazabilidad durante un intento (incidencia de
 * foco, intento de salida o uso de una herramienta permitida). El intento se toma de la
 * ruta y se valida que pertenezca al estudiante autenticado.
 *
 * <p>Solo admite metadata segura y acotada: la {@code description} es breve y no
 * sensible; {@code tool} identifica la herramienta abierta ({@code TOOL_OPENED}/
 * {@code TOOL_RETURNED}); {@code source} es una etiqueta corta del origen del evento
 * (p. ej. {@code VISIBILITY_CHANGE}, {@code BLUR}, {@code BUTTON_EXIT}) que el backend
 * sanitiza. Nunca se aceptan respuestas, claves, tokens ni datos sensibles. Los hitos del
 * ciclo de vida del intento (inicio, envío, expiración, salida confirmada) los registra el
 * backend, no el cliente.</p>
 */
public record RegisterAttemptEventRequest(

        @NotNull(message = "El tipo de evento es obligatorio")
        AttemptEventType eventType,

        @Size(max = 200, message = "La descripción no puede superar 200 caracteres")
        String description,

        // Herramienta abierta (solo aplica a TOOL_OPENED/TOOL_RETURNED). Opcional.
        AttemptTool tool,

        // Origen del evento; etiqueta corta que el backend limpia antes de guardar. Opcional.
        @Size(max = 60, message = "El origen no puede superar 60 caracteres")
        String source

) {}
