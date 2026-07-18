package com.morales.chemicallab.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migración de esquema para la pizarra interactiva.
 *
 * <p>Con {@code ddl-auto=update}, Hibernate genera una restricción CHECK sobre las columnas
 * de enum persistidas ({@code whiteboard_sessions.status} y
 * {@code whiteboard_participants.interaction_override}) con los valores existentes al crear la
 * tabla. Si en el futuro se amplía alguno de esos enums, {@code ddl-auto=update} <b>no</b>
 * actualiza el CHECK y cualquier inserción con un valor nuevo devolvería HTTP 500 (mismo patrón
 * ya visto en evaluaciones). Para prevenirlo, aquí se eliminan esos CHECK heredados de forma
 * idempotente; la validación de los valores la garantiza el mapeo JPA del enum.</p>
 *
 * <p>Cada sentencia usa {@code DROP CONSTRAINT IF EXISTS}, por lo que es segura de ejecutar
 * siempre. Un fallo aquí se registra pero nunca interrumpe el arranque.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class WhiteboardSchemaMigration implements ApplicationRunner {

    private static final String DROP_SESSION_STATUS_CHECK =
            "ALTER TABLE whiteboard_sessions "
                    + "DROP CONSTRAINT IF EXISTS whiteboard_sessions_status_check";

    private static final String DROP_PARTICIPANT_OVERRIDE_CHECK =
            "ALTER TABLE whiteboard_participants "
                    + "DROP CONSTRAINT IF EXISTS whiteboard_participants_interaction_override_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        runQuietly(DROP_SESSION_STATUS_CHECK,
                "Restricción CHECK heredada de status de pizarra eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de whiteboard_sessions.status");
        runQuietly(DROP_PARTICIPANT_OVERRIDE_CHECK,
                "Restricción CHECK heredada de interaction_override eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de whiteboard_participants.interaction_override");
    }

    /** Ejecuta una sentencia DDL idempotente sin interrumpir el arranque si falla. */
    private void runQuietly(String sql, String successMessage, String failurePrefix) {
        try {
            jdbcTemplate.execute(sql);
            log.info(successMessage);
        } catch (Exception ex) {
            log.warn("{}: {}", failurePrefix, ex.getMessage());
        }
    }
}
