package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
