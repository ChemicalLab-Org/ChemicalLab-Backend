package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

/**
 * Datos del usuario autenticado que devuelve GET /api/auth/me. A diferencia de
 * {@link AuthResponse}, no incluye el token (el cliente ya lo posee) ni ningún dato sensible
 * como el hash de la contraseña. Permite al frontend validar que la sesión almacenada sigue
 * siendo válida y vigente al arrancar la aplicación.
 */
public record CurrentUserResponse(
        Long userId,
        String username,
        String email,
        String names,
        String lastNames,
        Role role,
        Boolean active,
        Boolean temporaryPassword
) {}
