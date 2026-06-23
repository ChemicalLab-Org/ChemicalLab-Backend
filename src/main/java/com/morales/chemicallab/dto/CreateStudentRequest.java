package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record CreateStudentRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        String names,

        @NotBlank(message = "Los apellidos son obligatorios")
        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres")
        String lastNames,

        @Size(max = 20, message = "El código de estudiante no puede superar 20 caracteres")
        String studentCode,

        @NotBlank(message = "La contraseña temporal es obligatoria")
        @Size(min = 6, max = 100, message = "La contraseña debe tener entre 6 y 100 caracteres")
        String temporaryPassword,

        @NotBlank(message = "El grado es obligatorio")
        @Pattern(regexp = "^\\s*[1-6]\\s*$", message = "El grado debe ser un número entero del 1 al 6")
        String grade,

        @NotBlank(message = "La sección es obligatoria")
        @Pattern(regexp = "^\\s*[A-Za-z]\\s*$", message = "La sección debe ser una sola letra (A-Z)")
        String section

) {}
