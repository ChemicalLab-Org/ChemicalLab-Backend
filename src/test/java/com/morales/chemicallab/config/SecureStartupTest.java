package com.morales.chemicallab.config;

import com.morales.chemicallab.security.JwtKeyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.StandardEnvironment;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real SpringApplication config loading and post-processors; no DB, mocks, or web server. */
class SecureStartupTest {
    private static final AtomicInteger INITIALIZED = new AtomicInteger();

    @BeforeEach
    void reset() { INITIALIZED.set(0); }

    static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "malformed_secret-sensitive!", "YQ==", "YWJj\n", "YWJj=", "<REPLACE_ME>"})
    void productionRejectsInvalidKeysBeforeAnyBean(String key) {
        assertThatThrownBy(() -> start("--app.jwt.secret=" + key))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_JWT_SECRET");
        assertThat(INITIALIZED).hasValue(0);
    }

    @Test
    void productionRejectsAbsentAndPublicDevelopmentKeys() {
        assertThatThrownBy(() -> start()).hasMessageContaining("APP_JWT_SECRET");
        assertThatThrownBy(() -> start("--app.jwt.secret=" + JwtKeyValidator.DEVELOPMENT_SECRET))
                .hasMessageContaining("desarrollo");
        assertThat(INITIALIZED).hasValue(0);
    }

    @Test
    void diagnosticsDoNotIncludeSecretOrDecoderCause() {
        String secret = "private-sensitive-invalid!";
        assertThatThrownBy(() -> start("--app.jwt.secret=" + secret))
                .hasMessageNotContaining(secret).hasNoCause();
    }

    @Test
    void validExternalKeyAllowsInitializationAndNoProfileMeansProd() {
        try (var context = start("--app.jwt.secret=" + randomKey(),
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/fictitious",
                "--spring.datasource.username=fictitious", "--spring.datasource.password=fictitious")) {
            assertThat(RuntimeEnvironment.from(context.getEnvironment())).isEqualTo(RuntimeEnvironment.PROD);
            assertThat(INITIALIZED).hasValue(1);
        }
    }

    @Test
    void productionHasNoDatabaseFallbacks() {
        assertThatThrownBy(() -> start("--app.jwt.secret=" + randomKey()))
                .hasMessageContaining("SPRING_DATASOURCE_URL");
        assertThatThrownBy(() -> start("--app.jwt.secret=" + randomKey(),
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/fictitious"))
                .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
        assertThatThrownBy(() -> start("--app.jwt.secret=" + randomKey(),
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/fictitious",
                "--spring.datasource.username=fictitious"))
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
        assertThat(INITIALIZED).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev,prod", "test,prod", "dev,test", "production", "unknown"})
    void mixedOrUnknownProfilesFailBeforeAnyBean(String profile) {
        assertThatThrownBy(() -> start("--spring.profiles.active=" + profile))
                .hasMessageContaining("exactamente un perfil");
        assertThat(INITIALIZED).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void explicitNonProductionProfileAllowsLocalKeyAndDisablesProvisioning(String profile) {
        try (var context = start("--spring.profiles.active=" + profile)) {
            assertThat(context.getEnvironment().getProperty("app.demo.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("app.bootstrap.admin.enabled")).isEqualTo("false");
        }
    }

    @Test
    void invalidBootstrapFailsBeforeAnyBeanWithoutExposingPassword() {
        assertThatThrownBy(() -> start("--spring.profiles.active=dev", "--app.bootstrap.admin.enabled=true"))
                .hasMessageContaining("APP_BOOTSTRAP_ADMIN_USERNAME");
        assertThatThrownBy(() -> start("--spring.profiles.active=dev", "--app.bootstrap.admin.enabled=true",
                "--app.bootstrap.admin.username=firstadmin", "--app.bootstrap.admin.password=bad-private"))
                .hasMessageContaining("APP_BOOTSTRAP_ADMIN_PASSWORD").hasMessageNotContaining("bad-private");
        assertThat(INITIALIZED).hasValue(0);
    }

    private org.springframework.context.ConfigurableApplicationContext start(String... supplied) {
        var env = new StandardEnvironment();
        // Do not inherit workstation secrets, profile or database configuration.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var application = new SpringApplication(Probe.class);
        application.setEnvironment(env);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        var args = new ArrayList<>(Arrays.asList(supplied));
        args.add("--spring.main.banner-mode=off");
        args.add("--logging.level.root=OFF");
        args.add("--spring.config.location=classpath:/");
        return application.run(args.toArray(String[]::new));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean Object initialized() { INITIALIZED.incrementAndGet(); return new Object(); }
    }
}
