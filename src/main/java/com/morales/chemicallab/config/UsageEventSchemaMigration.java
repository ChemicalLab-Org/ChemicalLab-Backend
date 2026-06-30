package com.morales.chemicallab.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migración de esquema para las métricas de uso ({@code usage_events}).
 *
 * <p>Con {@code ddl-auto=update}, Hibernate generó una restricción CHECK sobre las columnas de
 * enum ({@code usage_events.module} y {@code usage_events.event_type}) con los valores existentes
 * cuando se creó la tabla. Al ampliarse después esos enums (p. ej. el módulo {@code WHITEBOARD} y
 * el evento {@code WHITEBOARD_SESSION_JOINED} de la pizarra), {@code ddl-auto=update} <b>no</b>
 * actualiza el CHECK, de modo que insertar un valor nuevo lo viola. Como
 * {@code UsageMetricService.recordEvent} se ejecuta dentro de la transacción de la operación que
 * lo dispara (p. ej. la unión del estudiante a la pizarra), esa violación marca la transacción
 * para rollback y la operación principal termina en HTTP 500 aunque su lógica fuera correcta
 * (mismo patrón ya visto en evaluaciones y contenidos).</p>
 *
 * <p>Para prevenirlo, aquí se eliminan esos CHECK heredados de forma idempotente
 * ({@code DROP CONSTRAINT IF EXISTS}); la validación de los valores la garantiza el mapeo JPA del
 * enum. Un fallo aquí se registra pero nunca interrumpe el arranque.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class UsageEventSchemaMigration implements ApplicationRunner {

    private static final String DROP_MODULE_CHECK =
            "ALTER TABLE usage_events DROP CONSTRAINT IF EXISTS usage_events_module_check";

    private static final String DROP_EVENT_TYPE_CHECK =
            "ALTER TABLE usage_events DROP CONSTRAINT IF EXISTS usage_events_event_type_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        runQuietly(DROP_MODULE_CHECK,
                "Restricción CHECK heredada de usage_events.module eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de usage_events.module");
        runQuietly(DROP_EVENT_TYPE_CHECK,
                "Restricción CHECK heredada de usage_events.event_type eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de usage_events.event_type");
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
