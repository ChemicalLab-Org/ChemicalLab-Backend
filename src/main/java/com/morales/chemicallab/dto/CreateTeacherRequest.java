package com.morales.chemicallab.dto;

import com.morales.chemicallab.validation.InputValidation;
import jakarta.validation.constraints.*;

public record CreateTeacherRequest(

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

        @NotBlank(message = "El nombre de usuario es obligatorio")
        @Size(min = 4, max = 50, message = "El nombre de usuario debe tener entre 4 y 50 caracteres")
        @Pattern(regexp = InputValidation.INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX,
                message = "El nombre de usuario solo puede contener letras y números")
        String username,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 100, message = "El correo no puede superar 100 caracteres")
        String email,

        @NotBlank(message = "La contraseña temporal es obligatoria")
        @Size(min = 6, max = 100, message = "La contraseña debe tener entre 6 y 100 caracteres")
        String temporaryPassword

) {}
