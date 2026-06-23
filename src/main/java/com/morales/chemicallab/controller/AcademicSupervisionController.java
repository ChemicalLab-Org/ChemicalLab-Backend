package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.SupervisionAssignmentResponse;
import com.morales.chemicallab.dto.SupervisionConceptResponse;
import com.morales.chemicallab.dto.SupervisionEvaluationResponse;
import com.morales.chemicallab.dto.SupervisionSummaryResponse;
import com.morales.chemicallab.service.AcademicSupervisionService;
import com.morales.chemicallab.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Panel administrativo de supervisión académica. Expone información institucional de
 * solo lectura sobre contenidos, evaluaciones, asignaciones y actividad general.
 *
 * <p>Todas las rutas cuelgan de {@code /api/admin/**}, restringido al rol
 * ADMINISTRADOR en {@code SecurityConfig}. Ningún endpoint permite modificación ni
 * expone la clave de respuestas de las evaluaciones.</p>
 */
@RestController
@RequestMapping("/api/admin/academic-supervision")
@RequiredArgsConstructor
public class AcademicSupervisionController {

    private final AcademicSupervisionService academicSupervisionService;
    private final AdminService adminService;

    @GetMapping("/summary")
    public SupervisionSummaryResponse resumen() {
        return academicSupervisionService.getSummary();
    }

    @GetMapping("/concepts")
    public List<SupervisionConceptResponse> contenidos() {
        return academicSupervisionService.listConcepts();
    }

    @GetMapping("/evaluations")
    public List<SupervisionEvaluationResponse> evaluaciones() {
        return academicSupervisionService.listEvaluations();
    }

    @GetMapping("/assignments")
    public List<SupervisionAssignmentResponse> asignaciones() {
        return academicSupervisionService.listAssignments();
    }

    @GetMapping("/activity")
    public AdminActivityResponse actividad() {
        return adminService.getActivity();
    }
}
