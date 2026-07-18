package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Respuesta del listado consolidado del registro de uso por estudiante: un resumen para
 * las tarjetas superiores del panel y la lista de registros por usuario. Los promedios
 * viajan como {@code null} cuando no hay datos suficientes para calcularlos (por ejemplo,
 * sin estudiantes con actividades asignadas), de modo que el frontend muestre
 * «No disponible» en lugar de un cero falso.
 */
public record StudentUsageRecordsResponse(
        Summary summary,
        List<StudentUsageRecordResponse> records) {

    public record Summary(
            long totalUsers,
            long studentsWithActivity,
            Double averageProgress,
            Double averageAccuracy,
            long totalSessionsStarted,
            String topModule,
            Long topModuleCount) {
    }
}
