package com.morales.chemicallab.service;

import java.util.regex.Pattern;

/**
 * Validación y normalización compartida de los campos académicos de un estudiante
 * (grado y sección). Es la fuente de verdad del backend: se aplica en todos los flujos
 * de creación y edición de estudiantes (admin y docente), de modo que nunca se persistan
 * valores inválidos aunque la validación del frontend falle o se omita.
 *
 * <ul>
 *   <li><strong>Grado:</strong> entero del 1 al 5. Rechaza 0, 6, 7, negativos, decimales,
 *       texto y vacío.</li>
 *   <li><strong>Sección:</strong> exactamente una letra (A-Z). Rechaza números, espacios
 *       internos y combinaciones como «3 C», «AA» o «A1». Se normaliza a mayúscula.</li>
 * </ul>
 */
final class StudentValidation {

    private static final Pattern GRADE_PATTERN = Pattern.compile("^[1-5]$");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^[A-Za-z]$");

    private StudentValidation() {
    }

    /** Valida el grado y devuelve su valor recortado; lanza {@link IllegalArgumentException} si es inválido. */
    static String normalizedGrade(String grade) {
        String trimmed = grade == null ? "" : grade.trim();
        if (!GRADE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("El grado debe ser un número entero del 1 al 5.");
        }
        return trimmed;
    }

    /** Valida la sección, la normaliza a mayúscula y la devuelve; lanza {@link IllegalArgumentException} si es inválida. */
    static String normalizedSection(String section) {
        String trimmed = section == null ? "" : section.trim();
        if (!SECTION_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("La sección debe ser una sola letra (A-Z).");
        }
        return trimmed.toUpperCase();
    }
}
