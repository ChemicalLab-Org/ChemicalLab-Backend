package com.morales.chemicallab.entity;

/**
 * Tipo de evento de trazabilidad registrado durante un intento de evaluación. Cubre
 * tanto las incidencias de foco del navegador (solo si la evaluación tiene activada la
 * detección de salida de pestaña, {@code trackTabExit}) como los hitos del ciclo de vida
 * del intento (inicio, envío, salida…) y el uso de herramientas permitidas.
 *
 * <p><b>Incidencias de foco</b> (requieren {@code trackTabExit}):</p>
 * <ul>
 *   <li>TAB_HIDDEN: la pestaña quedó oculta (cambio de pestaña, minimizar). Cuenta como
 *       una "salida" de la evaluación.</li>
 *   <li>TAB_VISIBLE: la pestaña volvió a estar visible (retorno a la evaluación).</li>
 *   <li>WINDOW_BLUR: la ventana perdió el foco. También cuenta como "salida".</li>
 *   <li>WINDOW_FOCUS: la ventana recuperó el foco.</li>
 * </ul>
 *
 * <p><b>Trazabilidad del intento</b> (se registran aunque {@code trackTabExit} esté
 * desactivado, porque no son incidencias de foco sino seguimiento del intento):</p>
 * <ul>
 *   <li>NAVIGATION_BLOCKED: el estudiante intentó navegar a otro módulo durante el
 *       intento y la aplicación lo impidió. Navegación interna, no pérdida de foco.</li>
 *   <li>TOOL_OPENED: el estudiante abrió una herramienta permitida (tabla periódica o
 *       formación de compuestos). La herramienta concreta viaja en {@code metadata}
 *       (p. ej. {@code tool=PERIODIC_TABLE}); nunca se registra qué consultó o formó.</li>
 *   <li>TOOL_RETURNED: el estudiante volvió al intento desde una herramienta.</li>
 *   <li>EXIT_ATTEMPTED: el estudiante pulsó "Salir del intento" (intención de salida).
 *       Puede cancelarse; no finaliza el intento por sí mismo.</li>
 * </ul>
 *
 * <p><b>Hitos del ciclo de vida</b> (los registra el backend, no el cliente):</p>
 * <ul>
 *   <li>ATTEMPT_STARTED: el intento se inició.</li>
 *   <li>ATTEMPT_SUBMITTED: el intento se envió normalmente.</li>
 *   <li>TIME_EXPIRED: el tiempo límite se agotó (el intento se cierra fuera de tiempo).</li>
 *   <li>ATTEMPT_EXITED: el estudiante confirmó la salida y el intento se dio por
 *       finalizado (abandono). No cuenta como "salida de pestaña".</li>
 * </ul>
 *
 * <p>Es una trazabilidad básica del intento, no una vigilancia perfecta ni un bloqueo.
 * Nunca se registra contenido de otras pestañas, capturas, historial del navegador,
 * respuestas, claves ni datos sensibles: solo el tipo de evento, el momento y, como
 * mucho, una metadata segura y acotada.</p>
 */
public enum AttemptEventType {
    // Incidencias de foco (requieren trackTabExit)
    TAB_HIDDEN,
    TAB_VISIBLE,
    WINDOW_BLUR,
    WINDOW_FOCUS,

    // Trazabilidad del intento (independiente de trackTabExit)
    NAVIGATION_BLOCKED,
    TOOL_OPENED,
    TOOL_RETURNED,
    EXIT_ATTEMPTED,

    // Hitos del ciclo de vida (los registra el backend)
    ATTEMPT_STARTED,
    ATTEMPT_SUBMITTED,
    TIME_EXPIRED,
    ATTEMPT_EXITED
}
