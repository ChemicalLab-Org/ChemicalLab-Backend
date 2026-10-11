package com.morales.chemicallab.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Argon2id writes; raw BCrypt reads. One verification of each algorithm for every attempt. */
public final class CompatiblePasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final PasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
    private final String dummyArgon = argon.encode(UUID.randomUUID().toString());
    private final String dummyBcrypt = bcrypt.encode(UUID.randomUUID().toString());

    @Override public String encode(CharSequence raw) {
        String value = raw == null ? null : raw.toString();
        PasswordPolicy.require(value);
        return argon.encode(value);
    }

    @Override public boolean matches(CharSequence raw, String encoded) {
        String value = raw == null ? "" : raw.toString();
        boolean bounded = value.length() <= 1024 && PasswordPolicy.wellFormed(value);
        String candidate = bounded ? value : "invalid-input";
        boolean legacy = encoded != null && encoded.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
        boolean modern = encoded != null && encoded.startsWith("$argon2id$");
        boolean bcryptCompatible = candidate.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean oldMatches = bcrypt.matches(bcryptCompatible ? candidate : "invalid-input",
                legacy && bcryptCompatible ? encoded : dummyBcrypt);
        boolean newMatches = argon.matches(candidate, modern ? encoded : dummyArgon);
        return bounded && ((legacy && bcryptCompatible && oldMatches) || (modern && newMatches));
    }

    @Override public boolean upgradeEncoding(String encoded) {
        return encoded != null && (encoded.startsWith("$2") || argon.upgradeEncoding(encoded));
    }

    /** Migration preserves the user's existing secret, even when it predates today's policy. */
    public String upgradeVerified(CharSequence raw) { return argon.encode(raw); }
}
