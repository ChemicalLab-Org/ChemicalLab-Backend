package com.morales.chemicallab.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputValidationTest {

    @Test
    void aceptaIdentificadoresInstitucionalesConLetrasYNumeros() {
        assertThat(InputValidation.requireInstitutionalIdentifier(
                "docente01", "nombre de usuario", 4, 50, false)).isEqualTo("docente01");
        assertThat(InputValidation.normalizeOptionalStudentCode(" est0001 ")).isEqualTo("EST0001");
    }

    @Test
    void rechazaSimbolosYEspaciosEnIdentificadoresInstitucionales() {
        assertThatThrownBy(() -> InputValidation.requireInstitutionalIdentifier(
                "docente.01", "nombre de usuario", 4, 50, false))
                .hasMessageContaining("letras y números");
        assertThatThrownBy(() -> InputValidation.normalizeOptionalStudentCode("EST-0001"))
                .hasMessageContaining("letras y números");
    }

    @Test
    void normalizaNombresRealesYRechazaDigitos() {
        assertThat(InputValidation.requirePersonName("  Ana   Lucía  ", "nombres"))
                .isEqualTo("Ana Lucía");
        assertThat(InputValidation.requirePersonName("María-José O'Connor", "nombres"))
                .isEqualTo("María-José O'Connor");
        assertThatThrownBy(() -> InputValidation.requirePersonName("Ana123", "nombres"))
                .hasMessageContaining("solo puede contener letras");
    }

    @Test
    void tituloDePizarraSoloAceptaLetrasNumerosYEspacios() {
        assertThat(InputValidation.requireWhiteboardTitle("  Óxidos   3 B  "))
                .isEqualTo("Óxidos 3 B");
        assertThat(InputValidation.requireWhiteboardTitle("Fe2O3")).isEqualTo("Fe2O3");
        assertThatThrownBy(() -> InputValidation.requireWhiteboardTitle("Clase #1"))
                .hasMessageContaining("letras, números y espacios");
    }
}
