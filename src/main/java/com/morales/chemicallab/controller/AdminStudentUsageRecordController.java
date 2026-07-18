package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.StudentUsageRecordDetailResponse;
import com.morales.chemicallab.dto.StudentUsageRecordsResponse;
import com.morales.chemicallab.service.StudentUsageRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de uso por estudiante para el panel administrativo (instrumento «Ficha de
 * registro automático de uso del sistema ChemicalLab»). Solo lectura y restringido al rol
 * ADMINISTRADOR en {@code SecurityConfig} ({@code /api/admin/**}).
 *
 * <p>Los filtros llegan como texto y se validan en el servicio: cualquier valor inválido
 * (rol o módulo desconocido, grado fuera de 1-5, fechas mal formadas o invertidas) produce
 * un 400 con mensaje claro, nunca un 500.</p>
 */
@RestController
@RequestMapping("/api/admin/student-usage-records")
@RequiredArgsConstructor
public class AdminStudentUsageRecordController {

    private final StudentUsageRecordService studentUsageRecordService;

    /**
     * Listado consolidado de indicadores por usuario. Todos los filtros son opcionales:
     * rol (o «TODOS»), búsqueda por nombre/usuario/código/email, grado (1-5), sección
     * (una letra), rango de fechas (AAAA-MM-DD), módulo del sistema y solo-con-actividad.
     */
    @GetMapping
    public StudentUsageRecordsResponse listar(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String onlyStudentsWithActivity) {
        return studentUsageRecordService.getRecords(
                role, search, grade, section, from, to, module, onlyStudentsWithActivity);
    }

    /** Detalle de un usuario: indicadores, eventos recientes, evaluaciones e incidencias. */
    @GetMapping("/{userId}")
    public StudentUsageRecordDetailResponse detalle(@PathVariable Long userId) {
        return studentUsageRecordService.getDetail(userId);
    }
}
