package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(

        @NotBlank(message = "La nueva contraseña temporal es obligatoria")
        @Size(min = 6, max = 100, message = "La contraseña debe tener entre 6 y 100 caracteres")
        String newTemporaryPassword

) {}
