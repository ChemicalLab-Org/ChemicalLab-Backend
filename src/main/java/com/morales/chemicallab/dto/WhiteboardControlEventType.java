package com.morales.chemicallab.dto;

/**
 * Evento de control difundido al canal de una sesión cuando el docente realiza una acción
 * por REST (pausar, reanudar, cerrar, cambiar permisos). Ayuda al frontend a reaccionar en
 * vivo sin volver a consultar la API.
 */
public enum WhiteboardControlEventType {
    SESSION_PAUSED,
    SESSION_RESUMED,
    SESSION_CLOSED,
    INTERACTION_UPDATED,
    PARTICIPANT_PERMISSION_UPDATED,
    /** Un estudiante se unió a la sesión (para refrescar el panel de participantes del docente). */
    PARTICIPANT_JOINED
}
