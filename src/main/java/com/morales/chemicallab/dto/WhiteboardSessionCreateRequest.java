package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de creación de una sesión de pizarra. El docente se resuelve del token, no del
 * cuerpo. El grado y la sección se validan y normalizan en el servicio con las mismas
 * reglas académicas del proyecto (grado 1-6, sección una letra A-Z en mayúscula).
 */
public record WhiteboardSessionCreateRequest(
        @NotBlank(message = "El nombre de la sesión es obligatorio.")
        @Size(max = 150, message = "El nombre no puede superar 150 caracteres.")
        String name,

        @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres.")
        String description,

        @NotBlank(message = "El grado es obligatorio.")
        String grade,

        @NotBlank(message = "La sección es obligatoria.")
        String section
) {
}
