package com.morales.chemicallab.config;

import com.morales.chemicallab.security.JwtKeyValidator;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/** Runs after config data, before bean creation, migrations, or any DB connection. */
public class SecureEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        RuntimeEnvironment runtime = RuntimeEnvironment.from(environment);
        JwtKeyValidator.validate(environment.getProperty("app.jwt.secret"), runtime == RuntimeEnvironment.PROD);
        if (runtime == RuntimeEnvironment.PROD) {
            require(environment, "spring.datasource.url", "SPRING_DATASOURCE_URL");
            require(environment, "spring.datasource.username", "SPRING_DATASOURCE_USERNAME");
            require(environment, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
            if (!environment.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql://")) {
                throw new IllegalStateException("SPRING_DATASOURCE_URL debe ser una URL JDBC de PostgreSQL sin credenciales incorporadas.");
            }
        }
        // Even in prod this flag is parsed, but the demo runner is never eligible there.
        enabled(environment, "app.demo.enabled");
        if (enabled(environment, "app.bootstrap.admin.enabled")) {
            BootstrapAdminSettings.validate(environment);
        }
    }

    public static boolean enabled(Environment environment, String property) {
        String value = environment.getProperty(property, "false");
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException("Configure " + property + " como true o false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static void require(Environment environment, String property, String variable) {
        if (environment.getProperty(property, "").isBlank()) {
            throw new IllegalStateException("Producción requiere " + variable + " externa; no hay fallback de desarrollo.");
        }
    }
}
