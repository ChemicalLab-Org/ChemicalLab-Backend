package com.morales.chemicallab.dto;

/**
 * Respuesta al restablecer la contraseña de un usuario desde el panel administrativo.
 * El backend genera una contraseña temporal y la devuelve una sola vez para que el
 * administrador la entregue al usuario; no se almacena en texto plano.
 *
 * @param message          mensaje de confirmación.
 * @param temporaryPassword contraseña temporal generada (texto plano, de un solo uso).
 */
public record AdminPasswordResetResponse(
        String message,
        String temporaryPassword
) {}
