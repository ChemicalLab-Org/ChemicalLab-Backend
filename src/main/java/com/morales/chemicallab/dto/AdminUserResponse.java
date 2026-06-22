package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

import java.time.LocalDateTime;

/**
 * Vista unificada de un usuario para el panel administrativo. Reúne en un solo
 * registro los datos básicos de cualquier rol (administrador, docente o estudiante)
 * sin exponer la contraseña.
 *
 * @param code             código de estudiante cuando el usuario es ESTUDIANTE; {@code null} en otros roles.
 * @param protectedAccount {@code true} para la cuenta del propio administrador autenticado,
 *                         que no admite acciones peligrosas (p. ej. desactivarse a sí mismo).
 */
public record AdminUserResponse(
        Long userId,
        String fullName,
        String username,
        String code,
        String email,
        Role role,
        Boolean active,
        Boolean temporaryPassword,
        LocalDateTime createdAt,
        boolean protectedAccount
) {}
