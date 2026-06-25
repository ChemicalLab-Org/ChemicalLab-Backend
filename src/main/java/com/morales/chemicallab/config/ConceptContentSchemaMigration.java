package com.morales.chemicallab.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Limpieza de esquema para la flexibilización de la categoría de los contenidos
 * conceptuales.
 *
 * <p>La columna {@code concept_contents.category} dejó de mapearse como enumeración
 * cerrada y pasó a ser texto libre. Mientras fue un {@code enum}, Hibernate generaba
 * una restricción CHECK ({@code concept_contents_category_check}) que solo admitía los
 * códigos antiguos (OXIDOS, HIDROXIDOS, ACIDOS, …). Con {@code ddl-auto=update} esa
 * restricción <strong>no</strong> se elimina automáticamente al cambiar el tipo de la
 * columna, por lo que seguiría rechazando cualquier categoría personalizada (e incluso
 * las clásicas escritas de otra forma, como «Óxidos») y provocaría un error 500 al
 * crear o editar contenidos.</p>
 *
 * <p>Este inicializador elimina dicha restricción de forma idempotente al arrancar. Es
 * seguro ejecutarlo siempre: si la restricción no existe, no hace nada. Un fallo aquí
 * nunca interrumpe el arranque de la aplicación.</p>
 *
 * <p>También relaja la obligatoriedad de la columna {@code explanation}: desde la
 * incorporación de materiales adjuntos, un contenido puede apoyarse solo en un archivo o
 * en enlaces externos, por lo que el texto explicativo dejó de ser obligatorio. Con
 * {@code ddl-auto=update} Hibernate no elimina por sí mismo el {@code NOT NULL} previo, así
 * que se hace aquí de forma idempotente.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ConceptContentSchemaMigration implements ApplicationRunner {

    // DROP ... IF EXISTS es idempotente: no falla si la restricción ya no está.
    private static final String DROP_LEGACY_CATEGORY_CHECK =
            "ALTER TABLE concept_contents DROP CONSTRAINT IF EXISTS concept_contents_category_check";

    // DROP NOT NULL es idempotente en PostgreSQL: si la columna ya admite nulos, no hace nada.
    private static final String DROP_EXPLANATION_NOT_NULL =
            "ALTER TABLE concept_contents ALTER COLUMN explanation DROP NOT NULL";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        runQuietly(DROP_LEGACY_CATEGORY_CHECK,
                "Restricción heredada de categoría de contenidos conceptuales verificada (eliminada si existía).",
                "No se pudo eliminar la restricción heredada concept_contents_category_check");
        runQuietly(DROP_EXPLANATION_NOT_NULL,
                "Columna explanation de contenidos conceptuales verificada (ahora admite nulos).",
                "No se pudo relajar la obligatoriedad de concept_contents.explanation");
    }

    /** Ejecuta una sentencia DDL idempotente sin interrumpir el arranque si falla. */
    private void runQuietly(String sql, String successMessage, String failurePrefix) {
        try {
            jdbcTemplate.execute(sql);
            log.info(successMessage);
        } catch (Exception ex) {
            // No es crítico para el arranque: se registra para diagnóstico y se continúa.
            log.warn("{}: {}", failurePrefix, ex.getMessage());
        }
    }
}
