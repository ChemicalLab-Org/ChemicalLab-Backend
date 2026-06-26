package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

import java.time.LocalDateTime;

/**
 * Vista unificada de un usuario para el panel administrativo. Reúne en un solo
 * registro los datos básicos de cualquier rol (administrador, docente o estudiante)
 * sin exponer la contraseña.
 *
 * <p>Los campos de perfil ({@code names}, {@code lastNames}, {@code grade},
 * {@code section}, {@code teacherUserId}) solo vienen poblados cuando aplican al rol y
 * permiten precargar el formulario de edición sin un endpoint de detalle adicional.</p>
 *
 * @param code             código de estudiante cuando el usuario es ESTUDIANTE; {@code null} en otros roles.
 * @param teacherUserId    id de cuenta del docente responsable cuando es ESTUDIANTE; {@code null} en otros roles.
 * @param protectedAccount {@code true} para la cuenta del propio administrador autenticado,
 *                         que no admite acciones peligrosas (p. ej. desactivarse a sí mismo).
 */
public record AdminUserResponse(
        Long userId,
        String fullName,
        String names,
        String lastNames,
        String username,
        String code,
        String email,
        Role role,
        Boolean active,
        Boolean temporaryPassword,
        LocalDateTime createdAt,
        Long teacherUserId,
        String grade,
        String section,
        boolean protectedAccount
) {}
