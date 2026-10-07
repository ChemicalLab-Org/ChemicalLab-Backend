package com.morales.chemicallab.config;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

/** Read only when explicitly enabled. No default account, password, or secret-bearing toString. */
public final class BootstrapAdminSettings {
    private BootstrapAdminSettings() { }

    public static void validate(Environment environment) {
        String username = environment.getProperty("app.bootstrap.admin.username", "");
        String password = environment.getProperty("app.bootstrap.admin.password", "");
        String email = environment.getProperty("app.bootstrap.admin.email", "");
        if (!username.matches("[A-Za-z0-9]{4,50}")) {
            throw new IllegalStateException("Configure APP_BOOTSTRAP_ADMIN_USERNAME: 4 a 50 letras o números, sin espacios.");
        }
        if (password.isBlank() || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.equals("Admin123*")) {
            throw new IllegalStateException("Configure APP_BOOTSTRAP_ADMIN_PASSWORD: contraseña externa de al menos 12 caracteres y máximo 72 bytes UTF-8.");
        }
        if (!email.isEmpty() && (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_EMAIL es opcional; si se define debe ser un correo válido sin espacios.");
        }
    }
}
