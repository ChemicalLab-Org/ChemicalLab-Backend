package com.morales.chemicallab.entity;

/**
 * Tipo de incidencia de foco detectada por el navegador durante un intento, cuando la
 * evaluación tiene activada la detección de salida de pestaña ({@code trackTabExit}).
 *
 * <ul>
 *   <li>TAB_HIDDEN: la pestaña quedó oculta (cambio de pestaña, minimizar). Se considera
 *       una "salida" de la evaluación.</li>
 *   <li>TAB_VISIBLE: la pestaña volvió a estar visible (retorno a la evaluación).</li>
 *   <li>WINDOW_BLUR: la ventana perdió el foco. También se considera una "salida".</li>
 *   <li>WINDOW_FOCUS: la ventana recuperó el foco.</li>
 *   <li>NAVIGATION_BLOCKED: el estudiante intentó navegar a otro módulo durante el
 *       intento y la aplicación lo impidió. Es navegación interna, no pérdida de foco,
 *       por lo que no cuenta como "salida de pestaña".</li>
 * </ul>
 *
 * <p>Es una detección básica de pérdida de foco del navegador, no una vigilancia
 * perfecta ni un bloqueo. Nunca se registra contenido de otras pestañas, capturas ni
 * datos sensibles: solo el tipo de evento y el momento.</p>
 */
public enum AttemptEventType {
    TAB_HIDDEN,
    TAB_VISIBLE,
    WINDOW_BLUR,
    WINDOW_FOCUS,
    NAVIGATION_BLOCKED
}
