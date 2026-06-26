package com.morales.chemicallab.config;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea los usuarios iniciales si no existen al arrancar la aplicación.
 *
 * Usuarios creados:
 *   - Administrador (admin)
 *   - Docente de prueba (docente) — solo para desarrollo
 *   - Estudiante de prueba (EST0001) asociado al docente — solo para desarrollo
 *
 * Contraseñas iniciales:
 *   - Se leen de variables de entorno (ADMIN_INITIAL_PASSWORD, TEACHER_INITIAL_PASSWORD,
 *     STUDENT_INITIAL_PASSWORD).
 *   - Si no están definidas, se usan los fallbacks de desarrollo.
 *   - Para producción, definir siempre las variables de entorno correspondientes.
 *
 * Cambiar la contraseña tras el primer inicio:
 *   1. Iniciar sesión con el username y la contraseña inicial.
 *   2. El campo temporaryPassword=true forzará la pantalla de cambio de contraseña.
 *   3. Seguir el flujo de cambio de contraseña temporal.
 *
 * El docente y el estudiante de prueba existen para facilitar las pruebas en desarrollo
 * (la base de datos se recrea en cada arranque por ddl-auto=create). No deben usarse en producción.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_EMAIL = "admin@chemicallab.local";
    // Fallback solo para desarrollo local — nunca usar en producción
    private static final String ADMIN_DEV_FALLBACK_PASSWORD = "Admin123*";

    private static final String TEACHER_USERNAME = "docente";
    private static final String TEACHER_EMAIL = "docente@chemicallab.local";
    private static final String TEACHER_DEV_FALLBACK_PASSWORD = "Docente123*";

    private static final String STUDENT_USERNAME = "EST0001";
    private static final String STUDENT_DEV_FALLBACK_PASSWORD = "Estudiante123*";

    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        TeacherProfile teacher = seedTeacher();
        seedStudent(teacher);
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    private void seedAdmin() {
        if (adminAlreadyExists()) {
            log.info("Admin user already exists — skipping admin seed");
            return;
        }

        String rawPassword = resolvePassword("ADMIN_INITIAL_PASSWORD", ADMIN_DEV_FALLBACK_PASSWORD);

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

    // =========================================================================
    // DOCENTE DE PRUEBA (solo desarrollo)
    // =========================================================================

    private TeacherProfile seedTeacher() {
        if (userAccountRepository.existsByUsername(TEACHER_USERNAME)) {
            log.info("Demo teacher already exists — skipping teacher seed");
            return userAccountRepository.findByUsername(TEACHER_USERNAME)
                    .flatMap(teacherProfileRepository::findByUser)
                    .orElse(null);
        }

        String rawPassword = resolvePassword("TEACHER_INITIAL_PASSWORD", TEACHER_DEV_FALLBACK_PASSWORD);

        UserAccount user = UserAccount.builder()
                .username(TEACHER_USERNAME)
                .email(TEACHER_EMAIL)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.DOCENTE)
                .active(true)
                .temporaryPassword(true)
                .build();

        userAccountRepository.save(user);

        TeacherProfile teacher = TeacherProfile.builder()
                .user(user)
                .names("Docente")
                .lastNames("Demo")
                .build();

        teacherProfileRepository.save(teacher);
        log.info("Demo teacher created. Username: [{}]. Set TEACHER_INITIAL_PASSWORD env var to override the default password.", TEACHER_USERNAME);
        return teacher;
    }

    // =========================================================================
    // ESTUDIANTE DE PRUEBA (solo desarrollo)
    // =========================================================================

    private void seedStudent(TeacherProfile teacher) {
        if (teacher == null) {
            log.warn("Demo teacher unavailable — skipping demo student seed");
            return;
        }

        if (userAccountRepository.existsByUsername(STUDENT_USERNAME)
                || studentProfileRepository.existsByStudentCode(STUDENT_USERNAME)) {
            log.info("Demo student already exists — skipping student seed");
            return;
        }

        String rawPassword = resolvePassword("STUDENT_INITIAL_PASSWORD", STUDENT_DEV_FALLBACK_PASSWORD);

        UserAccount user = UserAccount.builder()
                .username(STUDENT_USERNAME)
                .email(null)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.ESTUDIANTE)
                .active(true)
                .temporaryPassword(true)
                .build();

        userAccountRepository.save(user);

        StudentProfile student = StudentProfile.builder()
                .user(user)
                .teacher(teacher)
                .studentCode(STUDENT_USERNAME)
                .names("Estudiante")
                .lastNames("Demo")
                .grade("5")
                .section("A")
                .build();

        studentProfileRepository.save(student);
        log.info("Demo student created. Username: [{}]. Set STUDENT_INITIAL_PASSWORD env var to override the default password.", STUDENT_USERNAME);
    }

    // =========================================================================
    // AUXILIARES
    // =========================================================================

    private String resolvePassword(String envVar, String devFallback) {
        String envPassword = System.getenv(envVar);
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        // Fallback de desarrollo — configura la variable de entorno en producción
        return devFallback;
    }
}
