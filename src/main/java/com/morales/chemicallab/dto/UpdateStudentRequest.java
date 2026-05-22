package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record UpdateStudentRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        String names,

        @NotBlank(message = "Los apellidos son obligatorios")
        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres")
        String lastNames,

        @Size(max = 20, message = "El código de estudiante no puede superar 20 caracteres")
        String studentCode,

        @NotBlank(message = "El grado es obligatorio")
        @Size(max = 20, message = "El grado no puede superar 20 caracteres")
        String grade,

        @NotBlank(message = "La sección es obligatoria")
        @Size(max = 20, message = "La sección no puede superar 20 caracteres")
        String section,

        @NotNull(message = "El campo activo es obligatorio")
        Boolean active

) {}
