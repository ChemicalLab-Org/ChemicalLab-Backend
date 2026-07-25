package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.validation.InputValidation;
import jakarta.validation.constraints.*;

/**
 * Solicitud para crear un usuario desde el panel administrativo. Un único formulario
 * cubre los tres roles; los campos aplicables dependen del rol y se validan en el
 * servicio para poder dar mensajes precisos por rol:
 *
 * <ul>
 *   <li><strong>ADMINISTRADOR:</strong> usa {@code username} y, opcionalmente, {@code email}.
 *       No tiene perfil con nombres.</li>
 *   <li><strong>DOCENTE:</strong> usa {@code names}, {@code lastNames}, {@code username} y,
 *       opcionalmente, {@code email}.</li>
 *   <li><strong>ESTUDIANTE:</strong> usa {@code names}, {@code lastNames}, {@code grade},
 *       {@code section} y {@code teacherUserId} (docente responsable). El {@code studentCode}
 *       es opcional: si no se envía, el backend lo genera y se usa como nombre de usuario.</li>
 * </ul>
 *
 * <p>La contraseña temporal no se recibe aquí: el backend la genera y la devuelve una
 * sola vez en la respuesta de creación, igual que en el restablecimiento.</p>
 */
public record CreateUserRequest(

        @NotNull(message = "El rol es obligatorio")
        Role role,

        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        @Pattern(regexp = InputValidation.PERSON_NAME_REQUEST_REGEX,
                message = "El nombre solo puede contener letras, espacios, apóstrofes y guiones")
        String names,

        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres")
        @Pattern(regexp = InputValidation.PERSON_NAME_REQUEST_REGEX,
                message = "Los apellidos solo pueden contener letras, espacios, apóstrofes y guiones")
        String lastNames,

        @Size(min = 4, max = 50, message = "El nombre de usuario debe tener entre 4 y 50 caracteres")
        @Pattern(regexp = InputValidation.INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX,
                message = "El nombre de usuario solo puede contener letras y números")
        String username,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 100, message = "El correo no puede superar 100 caracteres")
        String email,

        @Size(max = 20, message = "El grado no puede superar 20 caracteres")
        String grade,

        @Size(max = 20, message = "La sección no puede superar 20 caracteres")
        String section,

        @Size(max = 20, message = "El código de estudiante no puede superar 20 caracteres")
        @Pattern(regexp = InputValidation.OPTIONAL_INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX,
                message = "El código de estudiante solo puede contener letras y números")
        String studentCode,

        Long teacherUserId

) {}
