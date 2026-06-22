package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
