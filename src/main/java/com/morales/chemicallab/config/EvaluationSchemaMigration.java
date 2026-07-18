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
 * <p>Para {@code evaluation_attempt_events} sí hace falta una corrección: Hibernate creó
 * una restricción CHECK sobre {@code event_type} con los únicos valores que el enum
 * {@code AttemptEventType} tenía cuando se creó la tabla
 * ({@code TAB_HIDDEN, TAB_VISIBLE, WINDOW_BLUR, WINDOW_FOCUS}). Al ampliarse el enum con
 * los hitos del intento (inicio, envío, salida), el uso de herramientas y el intento de
 * salida, {@code ddl-auto=update} <b>no</b> actualiza ese CHECK, por lo que insertar un
 * evento de un tipo nuevo (p. ej. {@code ATTEMPT_STARTED} al iniciar el intento) viola la
 * restricción y devuelve HTTP 500. Aquí se elimina ese CHECK heredado de forma idempotente;
 * la validación de los valores la garantiza el mapeo JPA del enum.</p>
 *
 * <p>La sesión de preguntas abiertas y calificación manual reproduce el mismo problema en
 * otras tres columnas de enum, por lo que se eliminan sus CHECK heredados igual:
 * {@code evaluation_questions.question_type} (se generó solo con {@code MULTIPLE_CHOICE} y
 * rechazaba {@code OPEN_TEXT}, causando el 500 al crear una pregunta abierta),
 * {@code evaluation_attempts.status} (no admitía {@code PENDING_MANUAL_REVIEW}, que se
 * asigna al enviar un intento con preguntas abiertas) y {@code system_logs.event_type} (no
 * incluía los nuevos tipos de evento de la revisión manual, lo que rompía la trazabilidad).</p>
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

    private static final String ADD_ALLOW_PERIODIC_TABLE =
            "ALTER TABLE evaluations ADD COLUMN IF NOT EXISTS allow_periodic_table "
                    + "boolean NOT NULL DEFAULT false";

    // Orden de preguntas y avance por intento (modo una por una / orden aleatorio).
    private static final String ADD_ATTEMPT_QUESTION_ORDER =
            "ALTER TABLE evaluation_attempts ADD COLUMN IF NOT EXISTS question_order text";

    private static final String ADD_ATTEMPT_CURRENT_INDEX =
            "ALTER TABLE evaluation_attempts ADD COLUMN IF NOT EXISTS current_question_index "
                    + "integer NOT NULL DEFAULT 0";

    // CHECK heredado de event_type que solo admite los 4 valores originales del enum.
    // Al crecer AttemptEventType, ddl-auto=update no lo actualiza y rechaza los tipos
    // nuevos (p. ej. ATTEMPT_STARTED), provocando un 500 al iniciar el intento.
    private static final String DROP_EVENT_TYPE_CHECK =
            "ALTER TABLE evaluation_attempt_events "
                    + "DROP CONSTRAINT IF EXISTS evaluation_attempt_events_event_type_check";

    // CHECK heredado de question_type: cuando se creó la tabla, QuestionType solo tenía
    // MULTIPLE_CHOICE, así que Hibernate generó un CHECK que solo admite ese valor. Al
    // añadir OPEN_TEXT (preguntas abiertas), ddl-auto=update no actualiza el CHECK, por lo
    // que crear una pregunta abierta viola la restricción y devuelve HTTP 500.
    private static final String DROP_QUESTION_TYPE_CHECK =
            "ALTER TABLE evaluation_questions "
                    + "DROP CONSTRAINT IF EXISTS evaluation_questions_question_type_check";

    // CHECK heredado de status del intento: solo admitía IN_PROGRESS, SUBMITTED y GRADED.
    // Al añadir PENDING_MANUAL_REVIEW (intentos con preguntas abiertas pendientes de
    // calificación), insertar ese estado al enviar el intento violaría el CHECK y daría 500.
    private static final String DROP_ATTEMPT_STATUS_CHECK =
            "ALTER TABLE evaluation_attempts "
                    + "DROP CONSTRAINT IF EXISTS evaluation_attempts_status_check";

    // CHECK heredado de event_type de los logs: no incluye los tipos nuevos de esta sesión
    // (EVALUATION_OPEN_QUESTION_SAVED, EVALUATION_ATTEMPT_PENDING_REVIEW,
    // EVALUATION_ANSWER_REVIEWED, EVALUATION_REVIEW_COMPLETED, entre otros). Al registrar
    // uno de esos eventos se viola el CHECK; aunque el guardado de logs no propaga el error
    // (no rompe la operación), sí se pierde la trazabilidad. Eliminarlo la restablece.
    private static final String DROP_LOG_EVENT_TYPE_CHECK =
            "ALTER TABLE system_logs "
                    + "DROP CONSTRAINT IF EXISTS system_logs_event_type_check";

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
        runQuietly(ADD_ALLOW_PERIODIC_TABLE,
                "Columna allow_periodic_table de evaluaciones verificada.",
                "No se pudo agregar evaluations.allow_periodic_table");
        runQuietly(ADD_ATTEMPT_QUESTION_ORDER,
                "Columna question_order de intentos verificada.",
                "No se pudo agregar evaluation_attempts.question_order");
        runQuietly(ADD_ATTEMPT_CURRENT_INDEX,
                "Columna current_question_index de intentos verificada.",
                "No se pudo agregar evaluation_attempts.current_question_index");
        runQuietly(DROP_EVENT_TYPE_CHECK,
                "Restricción CHECK heredada de event_type eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de evaluation_attempt_events.event_type");
        runQuietly(DROP_QUESTION_TYPE_CHECK,
                "Restricción CHECK heredada de question_type eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de evaluation_questions.question_type");
        runQuietly(DROP_ATTEMPT_STATUS_CHECK,
                "Restricción CHECK heredada de status del intento eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de evaluation_attempts.status");
        runQuietly(DROP_LOG_EVENT_TYPE_CHECK,
                "Restricción CHECK heredada de event_type de logs eliminada (o ausente).",
                "No se pudo eliminar el CHECK heredado de system_logs.event_type");
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
