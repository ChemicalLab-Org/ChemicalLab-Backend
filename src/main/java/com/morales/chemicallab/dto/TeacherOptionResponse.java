package com.morales.chemicallab.dto;

/**
 * Opción de docente para selectores del panel administrativo (por ejemplo, al elegir el
 * docente responsable de un estudiante). Solo expone lo necesario para mostrar y elegir;
 * el listado se limita a docentes activos.
 *
 * @param userId   id de la cuenta del docente (se usa al crear/editar estudiantes).
 * @param fullName nombre completo del docente.
 * @param username nombre de usuario del docente.
 */
public record TeacherOptionResponse(
        Long userId,
        String fullName,
        String username
) {}
