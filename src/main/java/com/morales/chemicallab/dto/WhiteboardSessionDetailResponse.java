package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Detalle de una sesión para el docente propietario: la metadata de la sesión más la lista
 * de participantes con su permiso individual y efectivo.
 */
public record WhiteboardSessionDetailResponse(
        WhiteboardSessionResponse session,
        List<WhiteboardParticipantResponse> participants
) {
}
