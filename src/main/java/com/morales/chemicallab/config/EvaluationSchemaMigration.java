package com.morales.chemicallab.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migración de esquema para la configuración avanzada de evaluaciones.
 *
 * <p>La sesión añade tres columnas obligatorias a {@code evaluations}
 * ({@code allow_chemical_calculator}, {@code track_tab_exit} y
 * {@code question_display_mode}). Con {@code ddl-auto=update}, agregar una columna
 * {@code NOT NULL} sin valor por defecto a una tabla que ya tiene filas puede fallar y,
 * según el caso, dejar el esquema incompleto. Para no romper las evaluaciones
 * existentes, aquí se agregan de forma idempotente con un valor por defecto seguro que
 * preserva el comportamiento anterior (calculadora y detección desactivadas, preguntas
 * todas juntas) y rellena las filas previas.</p>
 *
 * <p>Cada sentencia usa {@code ADD COLUMN IF NOT EXISTS}, por lo que es seguro
 * ejecutarla siempre: si la columna ya existe (esquema nuevo creado por Hibernate), no
 * hace nada. Un fallo aquí se registra pero nunca interrumpe el arranque.</p>
 *
 * <p>La tabla {@code evaluation_attempt_events} no requiere migración: al ser nueva,
 * Hibernate la crea automáticamente con {@code ddl-auto=update}.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class EvaluationSchemaMigration implements ApplicationRunner {

    private static final String ADD_ALLOW_CALCULATOR =
            "ALTER TABLE evaluations ADD COLUMN IF NOT EXISTS allow_chemical_calculator "
                    + "boolean NOT NULL DEFAULT false";

    private static final String ADD_TRACK_TAB_EXIT =
            "ALTER TABLE evaluations ADD COLUMN IF NOT EXISTS track_tab_exit "
                    + "boolean NOT NULL DEFAULT false";

    private static final String ADD_QUESTION_DISPLAY_MODE =
            "ALTER TABLE evaluations ADD COLUMN IF NOT EXISTS question_display_mode "
                    + "varchar(20) NOT NULL DEFAULT 'ALL_AT_ONCE'";

    private static final String ADD_RANDOMIZE_QUESTIONS =
            "ALTER TABLE evaluations ADD COLUMN IF NOT EXISTS randomize_questions "
                    + "boolean NOT NULL DEFAULT false";

    // Orden de preguntas y avance por intento (modo una por una / orden aleatorio).
    private static final String ADD_ATTEMPT_QUESTION_ORDER =
            "ALTER TABLE evaluation_attempts ADD COLUMN IF NOT EXISTS question_order text";

    private static final String ADD_ATTEMPT_CURRENT_INDEX =
            "ALTER TABLE evaluation_attempts ADD COLUMN IF NOT EXISTS current_question_index "
                    + "integer NOT NULL DEFAULT 0";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        runQuietly(ADD_ALLOW_CALCULATOR,
                "Columna allow_chemical_calculator de evaluaciones verificada.",
                "No se pudo agregar evaluations.allow_chemical_calculator");
        runQuietly(ADD_TRACK_TAB_EXIT,
                "Columna track_tab_exit de evaluaciones verificada.",
                "No se pudo agregar evaluations.track_tab_exit");
        runQuietly(ADD_QUESTION_DISPLAY_MODE,
                "Columna question_display_mode de evaluaciones verificada.",
                "No se pudo agregar evaluations.question_display_mode");
        runQuietly(ADD_RANDOMIZE_QUESTIONS,
                "Columna randomize_questions de evaluaciones verificada.",
                "No se pudo agregar evaluations.randomize_questions");
        runQuietly(ADD_ATTEMPT_QUESTION_ORDER,
                "Columna question_order de intentos verificada.",
                "No se pudo agregar evaluation_attempts.question_order");
        runQuietly(ADD_ATTEMPT_CURRENT_INDEX,
                "Columna current_question_index de intentos verificada.",
                "No se pudo agregar evaluation_attempts.current_question_index");
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
