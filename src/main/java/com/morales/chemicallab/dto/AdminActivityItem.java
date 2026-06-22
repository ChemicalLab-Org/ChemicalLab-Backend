package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Elemento simple de actividad reciente para el panel administrativo. Representa un
 * registro ya existente (un usuario, una evaluación o un contenido recién creado),
 * sin trazabilidad detallada: esa funcionalidad corresponde al módulo de logs.
 *
 * @param title    título principal (nombre del usuario o título del recurso).
 * @param subtitle dato secundario (rol/usuario o nombre del docente autor).
 * @param timestamp fecha de creación del registro.
 */
public record AdminActivityItem(
        String title,
        String subtitle,
        LocalDateTime timestamp
) {}
