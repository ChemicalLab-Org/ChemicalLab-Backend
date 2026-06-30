package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Resumen institucional de la pizarra para el administrador: conteos por estado, total de
 * participantes y las sesiones más recientes (metadata, sin captura).
 */
public record WhiteboardSummaryResponse(
        long totalSessions,
        long activeSessions,
        long pausedSessions,
        long closedSessions,
        long totalParticipants,
        List<WhiteboardAdminSessionResponse> recentSessions
) {
}
