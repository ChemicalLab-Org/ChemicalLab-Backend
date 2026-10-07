package com.morales.chemicallab.security;

import java.util.Arrays;
import java.util.Base64;

/** Never includes supplied values or decoder exceptions in configuration diagnostics. */
public final class JwtKeyValidator {
    public static final String DEVELOPMENT_SECRET =
            "Y2hlbWljYWxsYWItYmFja2VuZC1qd3QtZGV2LW9ubHktc2VjcmV0LXJvdGF0ZS1pbi1wcm9kdWN0aW9uLTIwMjYtbWluLTMyLWJ5dGVz";

    private JwtKeyValidator() { }

    public static byte[] validate(String secret, boolean production) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Configure APP_JWT_SECRET con una clave externa aleatoria en Base64 estándar.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw malformed();
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(secret)) {
            throw malformed();
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET requiere al menos 32 bytes aleatorios antes de codificar en Base64.");
        }
        if (production && Arrays.equals(bytes, Base64.getDecoder().decode(DEVELOPMENT_SECRET))) {
            throw new IllegalStateException("APP_JWT_SECRET no puede usar la clave pública de desarrollo en producción. Genere una nueva.");
        }
        return bytes;
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("APP_JWT_SECRET debe ser Base64 estándar canónico, sin espacios ni saltos de línea.");
    }
}
