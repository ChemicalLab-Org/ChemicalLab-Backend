package com.morales.chemicallab.validation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fuente de verdad del backend para nombres, identificadores institucionales y títulos
 * de pizarra. Todas las rutas de creación y edición deben pasar por estas funciones,
 * incluso si el DTO ya fue validado por Bean Validation.
 */
public final class InputValidation {

    public static final String PERSON_NAME_REQUEST_REGEX =
            "^\\s*[\\p{L}\\p{M}]+(?:(?:\\s+|['\\-\\x{2019}])[\\p{L}\\p{M}]+)*\\s*$";
    public static final String INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX =
            "^\\s*[A-Za-z0-9]+\\s*$";
    public static final String OPTIONAL_INSTITUTIONAL_IDENTIFIER_REQUEST_REGEX =
            "^(?:\\s*|\\s*[A-Za-z0-9]+\\s*)$";
    public static final String WHITEBOARD_TITLE_REQUEST_REGEX =
            "^\\s*[\\p{L}\\p{M}\\p{N}]+(?:\\s+[\\p{L}\\p{M}\\p{N}]+)*\\s*$";

    private static final Pattern PERSON_NAME_PATTERN = Pattern.compile(
            "^[\\p{L}\\p{M}]+(?:[ '\\-\\x{2019}][\\p{L}\\p{M}]+)*$");
    private static final Pattern INSTITUTIONAL_IDENTIFIER_PATTERN =
            Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern WHITEBOARD_TITLE_PATTERN = Pattern.compile(
            "^[\\p{L}\\p{M}\\p{N}]+(?: [\\p{L}\\p{M}\\p{N}]+)*$");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private InputValidation() {
    }

    public static String requirePersonName(String value, String label) {
        String normalized = normalizeSpaces(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("El campo " + label + " es obligatorio.");
        }
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("El campo " + label + " no puede superar 100 caracteres.");
        }
        if (!PERSON_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "El campo " + label + " solo puede contener letras, espacios, apóstrofes y guiones.");
        }
        return normalized;
    }

    public static String requireInstitutionalIdentifier(
            String value, String label, int minLength, int maxLength, boolean uppercase) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("El campo " + label + " es obligatorio.");
        }
        if (normalized.length() < minLength || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "El campo " + label + " debe tener entre " + minLength + " y "
                            + maxLength + " caracteres.");
        }
        if (!INSTITUTIONAL_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "El campo " + label + " solo puede contener letras y números.");
        }
        return uppercase ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }

    public static String normalizeOptionalStudentCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireInstitutionalIdentifier(value, "código de estudiante", 4, 20, true);
    }

    public static String requireWhiteboardTitle(String value) {
        String normalized = normalizeSpaces(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sesión es obligatorio.");
        }
        if (normalized.length() > 150) {
            throw new IllegalArgumentException("El nombre no puede superar 150 caracteres.");
        }
        if (!WHITEBOARD_TITLE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "El nombre de la sesión solo puede contener letras, números y espacios.");
        }
        return normalized;
    }

    private static String normalizeSpaces(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
    }
}
