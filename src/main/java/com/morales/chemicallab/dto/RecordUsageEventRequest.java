package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Cuerpo del registro de una métrica de uso enviado por el frontend.
 *
 * <p>El usuario y el rol <strong>no</strong> se aceptan aquí: se resuelven del token en el
 * servidor para que el cliente no pueda falsificarlos. La metadata es opcional, corta y se
 * sanitiza en el servidor (se descartan claves sensibles y valores demasiado largos).</p>
 */
public record RecordUsageEventRequest(
        @NotNull(message = "El módulo es obligatorio.")
        UsageModule module,

        @NotNull(message = "El tipo de evento es obligatorio.")
        UsageEventType eventType,

        @Size(max = 60, message = "El tipo de recurso es demasiado largo.")
        String resourceType,

        @Size(max = 60, message = "El identificador de recurso es demasiado largo.")
        String resourceId,

        @Size(max = 300, message = "La descripción es demasiado larga.")
        String description,

        Map<String, String> metadata
) {}
