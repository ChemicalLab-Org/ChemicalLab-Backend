package com.morales.chemicallab.config;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea el usuario administrador inicial si no existe al arrancar la aplicación.
 *
 * Contraseña inicial:
 *   - Se lee de la variable de entorno ADMIN_INITIAL_PASSWORD.
 *   - Si no está definida, se usa el fallback de desarrollo "Admin123*".
 *   - Para producción, definir siempre ADMIN_INITIAL_PASSWORD en el entorno.
 *
 * Cambiar la contraseña tras el primer inicio:
 *   1. Iniciar sesión con username=admin y la contraseña inicial.
 *   2. El campo temporaryPassword=true forzará la pantalla de cambio de contraseña.
 *   3. Seguir el flujo de cambio de contraseña temporal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_EMAIL = "admin@chemicallab.local";
    // Fallback solo para desarrollo local — nunca usar en producción
    private static final String DEV_FALLBACK_PASSWORD = "Admin123*";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (adminAlreadyExists()) {
            log.info("Admin user already exists — skipping initial seed");
            return;
        }

        String rawPassword = resolveInitialPassword();

        UserAccount admin = UserAccount.builder()
                .username(ADMIN_USERNAME)
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.ADMINISTRADOR)
                .active(true)
                .temporaryPassword(true)
                .build();

        userAccountRepository.save(admin);
        log.info("Initial admin user created. Username: [{}]. Set ADMIN_INITIAL_PASSWORD env var to override the default password.", ADMIN_USERNAME);
    }

    private boolean adminAlreadyExists() {
        return userAccountRepository.existsByUsername(ADMIN_USERNAME)
                || userAccountRepository.existsByEmail(ADMIN_EMAIL)
                || userAccountRepository.countByRole(Role.ADMINISTRADOR) > 0;
    }

    private String resolveInitialPassword() {
        String envPassword = System.getenv("ADMIN_INITIAL_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        // Fallback de desarrollo — configura ADMIN_INITIAL_PASSWORD en producción
        return DEV_FALLBACK_PASSWORD;
    }
}
