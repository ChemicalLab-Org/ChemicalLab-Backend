package com.morales.chemicallab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de la validación y normalización de grado y sección de estudiantes. Es la
 * fuente de verdad del backend, compartida por los flujos de admin y docente.
 */
class StudentValidationTest {

    // ===================== GRADO =====================

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "4", "5", "6"})
    void grado_aceptaEnterosDel1Al6(String grade) {
        assertThat(StudentValidation.normalizedGrade(grade)).isEqualTo(grade);
    }

    @Test
    void grado_recortaEspacios() {
        assertThat(StudentValidation.normalizedGrade("  4  ")).isEqualTo("4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "7", "-1", "1.5", "tercero", "12", "1 2"})
    void grado_rechazaValoresInvalidos(String grade) {
        assertThatThrownBy(() -> StudentValidation.normalizedGrade(grade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("del 1 al 6");
    }

    @Test
    void grado_rechazaVacioYNulo() {
        assertThatThrownBy(() -> StudentValidation.normalizedGrade(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StudentValidation.normalizedGrade(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===================== SECCIÓN =====================

    @Test
    void seccion_aceptaUnaLetraMayuscula() {
        assertThat(StudentValidation.normalizedSection("A")).isEqualTo("A");
        assertThat(StudentValidation.normalizedSection("C")).isEqualTo("C");
    }

    @Test
    void seccion_normalizaAMayusculaYRecortaEspacios() {
        assertThat(StudentValidation.normalizedSection("c")).isEqualTo("C");
        assertThat(StudentValidation.normalizedSection("  b  ")).isEqualTo("B");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3 C", "AA", "A1", "1", "Primero A", "A B", "??", "12"})
    void seccion_rechazaValoresInvalidos(String section) {
        assertThatThrownBy(() -> StudentValidation.normalizedSection(section))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("una sola letra");
    }

    @Test
    void seccion_rechazaVacioYNulo() {
        assertThatThrownBy(() -> StudentValidation.normalizedSection(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StudentValidation.normalizedSection(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
