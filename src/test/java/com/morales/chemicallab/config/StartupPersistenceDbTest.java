package com.morales.chemicallab.config;

import com.morales.chemicallab.ChemicalLabBackendApplication;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Full app, random HTTP port, real PostgreSQL/JPA/transactions/runners, fresh schemas per test. */
class StartupPersistenceDbTest {
    @Test
    void productionStartsEmptyAndPopulatedWithoutDemosAndPreservesBootstrapAndHistory() throws Exception {
        withSchema(schema -> {
            try (var context = start(schema, "prod", "--app.demo.enabled=true")) {
                assertThat(context.containsBean("demoAccountSeeder")).isFalse();
                assertThat(users(context).count()).isZero();
                assertThat(context.containsBean("adminBootstrap")).isFalse();
                var teacherUser = users(context).saveAndFlush(UserAccount.builder().username("existingteacher")
                        .password("existing-hash").role(Role.DOCENTE).temporaryPassword(false).build());
                var teacher = context.getBean(TeacherProfileRepository.class).saveAndFlush(TeacherProfile.builder()
                        .user(teacherUser).names("Existing").lastNames("Teacher").build());
                context.getBean(JdbcTemplate.class).update(
                        "INSERT INTO concept_contents (title, category, explanation, status, active, created_by_teacher_id) "
                                + "VALUES ('Fictitious history', 'History', 'Preserve me', 'DRAFT', true, ?)", teacher.getId());
            }
            String originalHash;
            try (var context = start(schema, "prod", "--app.demo.enabled=true", "--app.bootstrap.admin.enabled=true",
                    "--app.bootstrap.admin.username=firstadmin", "--app.bootstrap.admin.password=fictitious-temporary-password")) {
                assertThat(users(context).count()).isEqualTo(2);
                var admin = users(context).findByUsername("firstadmin").orElseThrow();
                assertThat(admin.getTemporaryPassword()).isTrue();
                assertThat(context.getBean(PasswordEncoder.class).matches("fictitious-temporary-password", admin.getPassword())).isTrue();
                originalHash = admin.getPassword();
                admin.setActive(false); // Any existing admin, including inactive, blocks bootstrap.
                admin.setTemporaryPassword(false);
                users(context).saveAndFlush(admin);
            }
            try (var context = start(schema, "prod", "--app.demo.enabled=true", "--app.bootstrap.admin.enabled=true",
                    "--app.bootstrap.admin.username=anotheradmin", "--app.bootstrap.admin.password=different-fictitious-password")) {
                assertThat(users(context).count()).isEqualTo(2);
                assertThat(users(context).findByUsername("anotheradmin")).isEmpty();
                var admin = users(context).findByUsername("firstadmin").orElseThrow();
                assertThat(admin.getPassword()).isEqualTo(originalHash);
                assertThat(admin.getActive()).isFalse();
                assertThat(admin.getTemporaryPassword()).isFalse();
                assertHistory(context);
            }
            try (var context = start(schema, "prod")) {
                assertThat(users(context).count()).isEqualTo(2);
                assertThat(context.containsBean("adminBootstrap")).isFalse();
                assertHistory(context);
                assertThat(users(context).findByUsername("docente")).isEmpty();
                assertThat(users(context).findByUsername("EST0001")).isEmpty();
            }
        });
    }

    @Test
    void demosAreOptInAndDisablingOrRestartingPreservesProfilesAndPasswords() throws Exception {
        withSchema(schema -> {
            try (var context = start(schema, "test")) {
                assertThat(users(context).count()).isZero();
            }
            String changedPassword;
            try (var context = start(schema, "test", "--app.demo.enabled=true")) {
                assertThat(users(context).count()).isEqualTo(2);
                assertThat(users(context).countByRole(Role.ADMINISTRADOR)).isZero();
                var teacher = context.getBean(TeacherProfileRepository.class)
                        .findByUser(users(context).findByUsername("docente").orElseThrow()).orElseThrow();
                teacher.setNames("Changed");
                context.getBean(TeacherProfileRepository.class).saveAndFlush(teacher);
                var student = users(context).findByUsername("EST0001").orElseThrow();
                changedPassword = context.getBean(PasswordEncoder.class).encode("changed-fictitious-password");
                student.setPassword(changedPassword);
                student.setActive(false);
                student.setTemporaryPassword(false);
                users(context).saveAndFlush(student);
            }
            for (String profile : List.of("dev", "test", "prod")) {
                try (var context = start(schema, profile, "--app.demo.enabled=true")) {
                    assertThat(users(context).count()).isEqualTo(2);
                    var student = users(context).findByUsername("EST0001").orElseThrow();
                    assertThat(student.getPassword()).isEqualTo(changedPassword);
                    assertThat(student.getActive()).isFalse();
                    assertThat(student.getTemporaryPassword()).isFalse();
                    assertThat(context.getBean(TeacherProfileRepository.class)
                            .findByUser(users(context).findByUsername("docente").orElseThrow()).orElseThrow().getNames()).isEqualTo("Changed");
                }
            }
            try (var context = start(schema, "test")) {
                assertThat(users(context).count()).isEqualTo(2);
                context.getBean(JdbcTemplate.class).update("DELETE FROM student_profiles WHERE student_code = 'EST0001'");
                context.getBean(JdbcTemplate.class).update("DELETE FROM user_accounts WHERE username = 'EST0001'");
            }
            try (var context = start(schema, "test")) {
                assertThat(users(context).findByUsername("EST0001")).isEmpty();
                assertThat(users(context).count()).isEqualTo(1);
            }
        });
    }

    private void assertHistory(ConfigurableApplicationContext context) {
        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT explanation FROM concept_contents WHERE title = 'Fictitious history'", String.class)).isEqualTo("Preserve me");
        assertThat(users(context).findByUsername("existingteacher").orElseThrow().getPassword()).isEqualTo("existing-hash");
        assertThat(context.getBean(TeacherProfileRepository.class).findAll().get(0).getNames()).isEqualTo("Existing");
    }

    private UserAccountRepository users(ConfigurableApplicationContext context) {
        return context.getBean(UserAccountRepository.class);
    }

    private String setting(String name) {
        String value = System.getProperty(name);
        if (value == null) throw new IllegalStateException("Las pruebas requieren -D" + name + " para PostgreSQL desechable.");
        return value;
    }

    private ConfigurableApplicationContext start(String schema, String profile, String... extra) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var application = new SpringApplication(ChemicalLabBackendApplication.class);
        application.setEnvironment(environment);
        var args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profile, "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + setting("spring.datasource.url") + "?currentSchema=" + schema,
                "--spring.datasource.username=" + setting("spring.datasource.username"),
                "--spring.datasource.password=" + setting("spring.datasource.password"),
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.jpa.hibernate.ddl-auto=update", "--app.jwt.secret=" + SecureStartupTest.randomKey(),
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR", "--spring.config.location=classpath:/"));
        args.addAll(List.of(extra));
        return application.run(args.toArray(String[]::new));
    }

    private void withSchema(Scenario scenario) throws Exception {
        String url = setting("spring.datasource.url");
        // Fail before connecting if someone attempts to reuse the school's database.
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")) {
            throw new IllegalStateException("Use únicamente PostgreSQL desechable en 127.0.0.1 con base chemicallab_t01_test y puerto explícito.");
        }
        String schema = "t01_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, setting("spring.datasource.username"), setting("spring.datasource.password"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                scenario.run(schema);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface Scenario { void run(String schema) throws Exception; }
}
