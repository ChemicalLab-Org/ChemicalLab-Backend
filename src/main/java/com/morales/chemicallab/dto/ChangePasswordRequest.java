package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record ChangePasswordRequest(

        @NotBlank(message = "La contraseña actual es obligatoria")
        @Size(max = 1024, message = "La contraseña actual supera el tamaño permitido")
        String currentPassword,

        @com.morales.chemicallab.validation.NewPassword
        String newPassword,

        @com.morales.chemicallab.validation.NewPassword
        String confirmPassword

) {}
