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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Datos ficticios únicamente con app.demo.enabled=true y perfil dev/test.
 * Nunca modifica cuentas/perfiles encontrados; no crea administradores.
 */
@Slf4j
@Component
@Profile("!prod & (dev | test)")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@Order(3)
@RequiredArgsConstructor
public class DemoAccountSeeder implements ApplicationRunner {

    private static final String TEACHER_USERNAME = "docente";
    private static final String TEACHER_EMAIL = "docente@chemicallab.local";
    private static final String TEACHER_DEV_FALLBACK_PASSWORD = "Docente123*";

    private static final String STUDENT_USERNAME = "EST0001";
    private static final String STUDENT_DEV_FALLBACK_PASSWORD = "Estudiante123*";

    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (RuntimeEnvironment.from(environment) == RuntimeEnvironment.PROD
                || !SecureEnvironmentPostProcessor.enabled(environment, "app.demo.enabled")) {
            return;
        }
        TeacherProfile teacher = seedTeacher();
        seedStudent(teacher);
    }

    // =========================================================================
    // DOCENTE DE PRUEBA (solo desarrollo)
    // =========================================================================

    private TeacherProfile seedTeacher() {
        if (userAccountRepository.existsByUsername(TEACHER_USERNAME)) {
            log.info("Demo teacher already exists — skipping teacher seed");
            return userAccountRepository.findByUsername(TEACHER_USERNAME)
                    .filter(user -> user.getRole() == Role.DOCENTE && Boolean.TRUE.equals(user.getActive()))
                    .flatMap(teacherProfileRepository::findByUser)
                    .orElse(null);
        }
        if (userAccountRepository.existsByEmail(TEACHER_EMAIL)) {
            log.warn("Demo teacher identity occupied — skipping demo seed");
            return null;
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
        String envPassword = environment.getProperty(envVar);
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        // Fallback público exclusivo para demos opt-in en desarrollo/pruebas.
        return devFallback;
    }
}
