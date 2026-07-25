package com.morales.chemicallab.dto;

import com.morales.chemicallab.validation.InputValidation;
import jakarta.validation.constraints.*;

public record UpdateStudentRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        @Pattern(regexp = InputValidation.PERSON_NAME_REQUEST_REGEX,
                message = "El nombre solo puede contener letras, espacios, apóstrofes y guiones")
        String names,

        @NotBlank(message = "Los apellidos son obligatorios")
        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres")
        @Pattern(regexp = InputValidation.PERSON_NAME_REQUEST_REGEX,
                message = "Los apellidos solo pueden contener letras, espacios, apóstrofes y guiones")
        String lastNames,

        @Size(max = 20, message = "El código de estudiante no puede superar 20 caracteres")
        @Pattern(regexp = InputValidation.OPTIONAL_INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX,
                message = "El código de estudiante solo puede contener letras y números")
        String studentCode,

        @NotBlank(message = "El grado es obligatorio")
        @Pattern(regexp = "^\\s*[1-5]\\s*$", message = "El grado debe ser un número entero del 1 al 5")
        String grade,

        @NotBlank(message = "La sección es obligatoria")
        @Pattern(regexp = "^\\s*[A-Za-z]\\s*$", message = "La sección debe ser una sola letra (A-Z)")
        String section,

        @NotNull(message = "El campo activo es obligatorio")
        Boolean active

) {}
