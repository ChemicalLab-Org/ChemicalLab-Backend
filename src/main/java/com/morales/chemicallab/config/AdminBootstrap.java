package com.morales.chemicallab.config;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!SecureEnvironmentPostProcessor.enabled(environment, "app.bootstrap.admin.enabled")) {
            return;
        }
        BootstrapAdminSettings.validate(environment);
        // Serialize simultaneous bootstraps across application instances on this PostgreSQL DB.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(740101)");
        if (users.countByRole(Role.ADMINISTRADOR) > 0) {
            log.info("Bootstrap omitido: ya existe un administrador. Deshabilite APP_BOOTSTRAP_ADMIN_ENABLED.");
            return;
        }
        String username = environment.getProperty("app.bootstrap.admin.username");
        String email = environment.getProperty("app.bootstrap.admin.email", "");
        if (users.existsByUsername(username) || (!email.isEmpty() && users.existsByEmail(email))) {
            throw new IllegalStateException("La identidad del bootstrap ya está ocupada; revise la cuenta existente sin sobrescribirla.");
        }
        users.save(UserAccount.builder()
                .username(username).email(email.isEmpty() ? null : email)
                .password(passwordEncoder.encode(environment.getProperty("app.bootstrap.admin.password")))
                .role(Role.ADMINISTRADOR).active(true).temporaryPassword(true).build());
        log.info("Primer administrador creado con contraseña temporal. Deshabilite APP_BOOTSTRAP_ADMIN_ENABLED y retire sus credenciales.");
    }
}
