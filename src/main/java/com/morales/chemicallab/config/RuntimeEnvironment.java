package com.morales.chemicallab.config;

import org.springframework.core.env.Environment;

import java.util.Set;

/** Exactly one runtime profile is required; an unconfigured runtime is production. */
public enum RuntimeEnvironment {
    DEV, TEST, PROD;

    public static RuntimeEnvironment from(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        if (profiles.length != 1 || !Set.of("dev", "test", "prod").contains(profiles[0])) {
            throw new IllegalStateException(
                    "Seleccione exactamente un perfil: SPRING_PROFILES_ACTIVE=dev, test o prod. No combine entornos.");
        }
        return valueOf(profiles[0].toUpperCase(java.util.Locale.ROOT));
    }
}
