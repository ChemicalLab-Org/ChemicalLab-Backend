package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Indicadores consolidados de uso del sistema para un usuario, alineados con la ficha de
 * registro automático de uso de ChemicalLab. Cada indicador se calcula únicamente sobre
 * datos reales ya persistidos; cuando un indicador no puede calcularse de forma confiable
 * (o no aplica al rol del usuario) viaja como {@code null} y el frontend lo muestra como
 * «No disponible», nunca como un cero engañoso.
 *
 * <p>{@code totalUsageMinutes} es siempre {@code null} en esta versión: la plataforma no
 * registra cierre de sesión ni duración de sesión, por lo que no existe base confiable
 * para calcular minutos acumulados sin inventar datos.</p>
 */
public record StudentUsageRecordResponse(
        Long userId,
        Long studentProfileId,
        String code,
        String username,
        String fullName,
        Role role,
        String grade,
        String section,
        Long totalUsageMinutes,
        Long sessionsStarted,
        Integer visitedModulesCount,
        List<String> visitedModules,
        Long assignedActivities,
        Long completedActivities,
        Double progressPercentage,
        Long attemptsCount,
        Long correctAnswers,
        Long incorrectAnswers,
        Double accuracyRate,
        Long feedbackReceived,
        Long technicalIncidentsCount,
        String technicalIncidentsSummary,
        LocalDateTime lastActivityAt) {
}
