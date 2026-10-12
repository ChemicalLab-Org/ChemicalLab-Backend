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
    public com.morales.chemicallab.dto.QueryPage<SupervisionConceptResponse> contenidos(@org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,@org.springframework.web.bind.annotation.RequestParam(defaultValue="25") int size,@org.springframework.web.bind.annotation.RequestParam(required=false) String search,@org.springframework.web.bind.annotation.RequestParam(required=false) String status,@org.springframework.web.bind.annotation.RequestParam(required=false) String category,@org.springframework.web.bind.annotation.RequestParam(required=false) String section) {
        return academicSupervisionService.listConcepts(page,size,search,status,category,section);
    }

    @GetMapping("/evaluations")
    public com.morales.chemicallab.dto.QueryPage<SupervisionEvaluationResponse> evaluaciones(@org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,@org.springframework.web.bind.annotation.RequestParam(defaultValue="25") int size,@org.springframework.web.bind.annotation.RequestParam(required=false) String search,@org.springframework.web.bind.annotation.RequestParam(required=false) String status,@org.springframework.web.bind.annotation.RequestParam(required=false) String section,@org.springframework.web.bind.annotation.RequestParam(defaultValue="false") boolean withAttempts) {
        return academicSupervisionService.listEvaluations(page,size,search,status,section,withAttempts);
    }

    @GetMapping("/assignments")
    public com.morales.chemicallab.dto.QueryPage<SupervisionAssignmentResponse> asignaciones(@org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,@org.springframework.web.bind.annotation.RequestParam(defaultValue="25") int size,@org.springframework.web.bind.annotation.RequestParam(required=false) String search,@org.springframework.web.bind.annotation.RequestParam(required=false) String type,@org.springframework.web.bind.annotation.RequestParam(required=false) String grade,@org.springframework.web.bind.annotation.RequestParam(required=false) String section,@org.springframework.web.bind.annotation.RequestParam(required=false) String active) {
        return academicSupervisionService.listAssignments(page,size,search,type,grade,section,active);
    }

    @GetMapping("/options")
    public com.morales.chemicallab.repository.SupervisionQueryRepository.Options options(){return academicSupervisionService.options();}

    @GetMapping("/activity")
    public AdminActivityResponse actividad() {
        return adminService.getActivity();
    }
}
