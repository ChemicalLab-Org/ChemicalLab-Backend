package com.morales.chemicallab.dto;

import jakarta.validation.constraints.*;

/**
 * Solicitud para actualizar los datos básicos de un usuario desde el panel
 * administrativo. Los campos aplicables dependen del rol del usuario y se validan en
 * el servicio:
 *
 * <ul>
 *   <li><strong>ADMINISTRADOR:</strong> solo {@code email}.</li>
 *   <li><strong>DOCENTE:</strong> {@code names}, {@code lastNames} y {@code email}.</li>
 *   <li><strong>ESTUDIANTE:</strong> {@code names}, {@code lastNames}, {@code grade},
 *       {@code section} y, opcionalmente, {@code teacherUserId} (docente responsable).</li>
 * </ul>
 *
 * <p>Por seguridad, esta operación no cambia el nombre de usuario ni el código (para no
 * romper relaciones existentes), no cambia el rol y no toca la contraseña ni el estado
 * activo/inactivo (este último se gestiona con los endpoints de activar/desactivar).</p>
 */
public record UpdateUserRequest(

        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        String names,

        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres")
        String lastNames,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 100, message = "El correo no puede superar 100 caracteres")
        String email,

        @Size(max = 20, message = "El grado no puede superar 20 caracteres")
        String grade,

        @Size(max = 20, message = "La sección no puede superar 20 caracteres")
        String section,

        Long teacherUserId

) {}
