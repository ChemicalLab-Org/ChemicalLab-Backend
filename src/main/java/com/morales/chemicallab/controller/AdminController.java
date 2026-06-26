package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserCreatedResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.dto.CreateUserRequest;
import com.morales.chemicallab.dto.TeacherOptionResponse;
import com.morales.chemicallab.dto.UpdateUserRequest;
import com.morales.chemicallab.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de apoyo al panel administrativo. Todos son de solo lectura y están
 * restringidos al rol ADMINISTRADOR en {@code SecurityConfig} ({@code /api/admin/**}).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/summary")
    public AdminSummaryResponse resumen() {
        return adminService.getSummary();
    }

    @GetMapping("/users")
    public List<AdminUserResponse> listarUsuarios() {
        return adminService.listUsers();
    }

    /** Docentes activos disponibles como docente responsable al crear/editar estudiantes. */
    @GetMapping("/users/teacher-options")
    public List<TeacherOptionResponse> opcionesDocentes() {
        return adminService.listActiveTeacherOptions();
    }

    /**
     * Crea un usuario (administrador, docente o estudiante). Devuelve el usuario creado y la
     * contraseña temporal generada una sola vez.
     */
    @PostMapping("/users")
    public ResponseEntity<AdminUserCreatedResponse> crearUsuario(@Valid @RequestBody CreateUserRequest request) {
        AdminUserCreatedResponse response = adminService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Actualiza los datos básicos de un usuario según su rol. */
    @PatchMapping("/users/{userId}")
    public AdminUserResponse actualizarUsuario(@PathVariable Long userId,
                                               @Valid @RequestBody UpdateUserRequest request) {
        return adminService.updateUser(userId, request);
    }

    /** Reactiva un usuario previamente desactivado. */
    @PatchMapping("/users/{userId}/activate")
    public AdminUserResponse activarUsuario(@PathVariable Long userId) {
        return adminService.activateUser(userId);
    }

    /** Desactiva un usuario (no lo elimina), con protección del propio admin y del último admin activo. */
    @PatchMapping("/users/{userId}/deactivate")
    public AdminUserResponse desactivarUsuario(@PathVariable Long userId) {
        return adminService.deactivateUser(userId);
    }

    @GetMapping("/activity")
    public AdminActivityResponse actividadReciente() {
        return adminService.getActivity();
    }

    /**
     * Restablece la contraseña de cualquier usuario administrable (docente, estudiante u
     * otro) por su id de cuenta. Devuelve la contraseña temporal generada una sola vez.
     */
    @PatchMapping("/users/{userId}/password/reset")
    public AdminPasswordResetResponse restablecerContrasena(@PathVariable Long userId) {
        return adminService.resetUserPassword(userId);
    }
}
