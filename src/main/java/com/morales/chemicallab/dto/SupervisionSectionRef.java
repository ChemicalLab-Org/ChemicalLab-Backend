package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Referencia a un grado/sección al que se asignó un contenido o una evaluación,
 * usada dentro de las vistas de supervisión del administrador. Solo expone el
 * destino académico y el estado de la asignación, sin datos sensibles.
 */
public record SupervisionSectionRef(
        String grade,
        String section,
        boolean active,
        LocalDateTime assignedAt
) {}
