package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Petición para agregar un enlace externo de apoyo a un contenido conceptual. La
 * validación del formato de la URL (solo http/https, sin esquemas peligrosos) se realiza
 * además en el servicio, de modo que la regla se cumpla aunque la petición no pase por la
 * validación de los DTOs.
 */
public record CreateMaterialLinkRequest(

        @Size(max = 150, message = "El título no puede superar 150 caracteres")
        String title,

        @NotBlank(message = "La URL es obligatoria")
        @Size(max = 2048, message = "La URL no puede superar 2048 caracteres")
        String url

) {}
