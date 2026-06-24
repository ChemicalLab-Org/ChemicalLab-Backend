package com.morales.chemicallab.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanitiza la metadata opcional de una métrica de uso antes de almacenarla.
 *
 * <p>Garantiza que las métricas de uso <strong>nunca</strong> guarden datos sensibles. Para
 * ello:</p>
 * <ul>
 *   <li>descarta claves consideradas sensibles (contraseñas, tokens, respuestas, claves de
 *       evaluación, datos personales innecesarios);</li>
 *   <li>limita la cantidad de entradas y la longitud de cada valor;</li>
 *   <li>serializa el resultado en un texto corto {@code clave=valor; clave2=valor2}.</li>
 * </ul>
 *
 * <p>Es deliberadamente conservador: ante la duda, descarta. No pretende reemplazar la
 * responsabilidad del frontend de no enviar datos sensibles, sino servir de última barrera.</p>
 */
final class UsageMetadataSanitizer {

    private UsageMetadataSanitizer() {
    }

    /** Máximo de entradas conservadas en la metadata. */
    private static final int MAX_ENTRIES = 8;
    /** Longitud máxima de cada valor antes de recortar. */
    private static final int MAX_VALUE_LENGTH = 120;
    /** Longitud máxima del texto serializado completo. */
    private static final int MAX_SERIALIZED_LENGTH = 500;

    /**
     * Fragmentos de clave que delatan un dato sensible. Si la clave (en minúsculas)
     * contiene cualquiera de estos, la entrada se descarta por completo.
     */
    private static final List<String> FORBIDDEN_KEY_FRAGMENTS = List.of(
            "password", "contrasena", "contraseña", "clave", "token", "secret", "jwt",
            "authorization", "credential", "answer", "respuesta", "correct", "alternativa",
            "solution", "apikey", "ssn", "dni"
    );

    /**
     * Devuelve una representación segura y corta de la metadata, o {@code null} si no queda
     * nada que guardar tras la sanitización.
     */
    static String sanitize(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (safe.size() >= MAX_ENTRIES) {
                break;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            if (isForbiddenKey(key)) {
                continue;
            }
            safe.put(sanitizeToken(key), truncate(sanitizeToken(value)));
        }

        if (safe.isEmpty()) {
            return null;
        }

        String serialized = safe.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        if (serialized.length() > MAX_SERIALIZED_LENGTH) {
            serialized = serialized.substring(0, MAX_SERIALIZED_LENGTH);
        }
        return serialized.isBlank() ? null : serialized;
    }

    private static boolean isForbiddenKey(String key) {
        String normalized = key.toLowerCase();
        return FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    /** Elimina separadores que romperían el formato {@code clave=valor; ...}. */
    private static String sanitizeToken(String raw) {
        return raw.replace("=", " ").replace(";", " ").trim();
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH);
    }
}
