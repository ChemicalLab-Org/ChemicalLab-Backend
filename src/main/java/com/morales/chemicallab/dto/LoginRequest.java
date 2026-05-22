package com.morales.chemicallab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank(message = "El usuario o correo es obligatorio")
        @Size(max = 100, message = "El usuario o correo no puede superar 100 caracteres")
        String usernameOrEmail,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 1, max = 100, message = "La contraseña no puede superar 100 caracteres")
        String password

) {}
