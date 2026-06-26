package com.morales.chemicallab.entity;

/**
 * Nivel de severidad de un evento de trazabilidad.
 *
 * <ul>
 *   <li>{@code INFO}: operación normal del sistema (login correcto, creación de recurso).</li>
 *   <li>{@code WARNING}: situación a vigilar (intento de login fallido, restablecimiento de contraseña).</li>
 *   <li>{@code ERROR}: fallo relevante registrado de forma intencional.</li>
 * </ul>
 */
public enum LogSeverity {
    INFO,
    WARNING,
    ERROR
}
