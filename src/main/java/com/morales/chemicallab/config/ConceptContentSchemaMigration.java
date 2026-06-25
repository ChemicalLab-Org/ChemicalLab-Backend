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
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ConceptContentSchemaMigration implements ApplicationRunner {

    // DROP ... IF EXISTS es idempotente: no falla si la restricción ya no está.
    private static final String DROP_LEGACY_CATEGORY_CHECK =
            "ALTER TABLE concept_contents DROP CONSTRAINT IF EXISTS concept_contents_category_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(DROP_LEGACY_CATEGORY_CHECK);
            log.info("Restricción heredada de categoría de contenidos conceptuales verificada (eliminada si existía).");
        } catch (Exception ex) {
            // No es crítico para el arranque: se registra para diagnóstico y se continúa.
            log.warn("No se pudo eliminar la restricción heredada concept_contents_category_check: {}",
                    ex.getMessage());
        }
    }
}
