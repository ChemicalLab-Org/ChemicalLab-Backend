package com.morales.chemicallab.dto;

/**
 * Respuesta al crear un usuario desde el panel administrativo. Devuelve el usuario
 * recién creado (sin datos sensibles) junto con la contraseña temporal generada, que
 * se entrega una sola vez para que el administrador la comunique al usuario. La
 * contraseña no se almacena en texto plano ni se registra en los logs.
 *
 * @param user              datos del usuario creado.
 * @param temporaryPassword contraseña temporal generada (texto plano, de un solo uso).
 */
public record AdminUserCreatedResponse(
        AdminUserResponse user,
        String temporaryPassword
) {}
