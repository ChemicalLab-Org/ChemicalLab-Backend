package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record ChangePasswordRequest(

        @NotBlank(message = "La contraseña actual es obligatoria")
        String currentPassword,

        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 6, max = 100, message = "La contraseña debe tener entre 6 y 100 caracteres")
        String newPassword,

        @NotBlank(message = "La confirmación de contraseña es obligatoria")
        @Size(min = 6, max = 100, message = "La confirmación debe tener entre 6 y 100 caracteres")
        String confirmPassword

) {}
