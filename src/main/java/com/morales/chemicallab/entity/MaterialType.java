package com.morales.chemicallab.entity;

/**
 * Tipo de material de apoyo asociado a un contenido conceptual.
 *
 * <ul>
 *   <li>{@link #FILE}: archivo subido por el docente (PDF, diapositivas o imagen),
 *       almacenado como bytes en la base de datos.</li>
 *   <li>{@link #LINK}: enlace externo de apoyo (video, simulador, recurso del colegio…).</li>
 * </ul>
 */
public enum MaterialType {
    FILE,
    LINK
}
