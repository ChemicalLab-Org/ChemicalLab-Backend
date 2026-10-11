package com.morales.chemicallab.security;

import com.morales.chemicallab.config.BootstrapAdminSettings;
import com.morales.chemicallab.dto.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    private final CompatiblePasswordEncoder encoder = new CompatiblePasswordEncoder();

    @Test void codePointsBytesPhrasesAndExactInput() {
        assertThat(PasswordPolicy.valid("abcd efgh ijklm")).isTrue(); // 15
        assertThat(PasswordPolicy.valid("abcd efgh ijkl")).isFalse(); // 14
        String maxAscii = "A long fictitious phrase ".repeat(6).substring(0, 128);
        String maxUnicode = "🧪🌊🌲🌙🔬".repeat(26).substring(0, 256); // 128 scalars, 512 bytes
        for (String value : new String[]{maxAscii, maxUnicode, "  Árboles y océanos  ", "Arbo\u0301les y oce\u0301anos"}) {
            assertThat(PasswordPolicy.valid(value)).isTrue();
            String hash = encoder.encode(value);
            assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
            assertThat(encoder.matches(value, hash)).isTrue();
            assertThat(encoder.matches(value + "x", hash)).isFalse();
        }
        assertThat(PasswordPolicy.valid(maxUnicode + "a")).isFalse();
        assertThat(PasswordPolicy.valid(maxAscii + "a")).isFalse();
        assertThat(PasswordPolicy.valid("MalformedUnicode\uD800")).isFalse();
        String hash = encoder.encode("  Árboles y océanos  ");
        assertThat(encoder.matches("Árboles y océanos", hash)).isFalse();
        assertThat(encoder.matches("  A\u0301rboles y oce\u0301anos  ", hash)).isFalse();
    }

    @Test void localCommonPasswordsAndAllNewDtoWriters() {
        for (String common : new String[]{"passwordpassword", "Password123456789", "administrador123", "a".repeat(80), "abcd".repeat(20)}) {
            assertThat(PasswordPolicy.valid(common)).isFalse();
            assertThatThrownBy(() -> encoder.encode(common)).isInstanceOf(IllegalArgumentException.class).hasMessage(PasswordPolicy.MESSAGE);
        }
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var validator = validation.getValidator();
            for (Object dto : new Object[]{new ChangePasswordRequest("legacy", "abcdef", "abcdef"),
                    new ResetPasswordRequest("abcdef", "abcdef"),
                    new CreateTeacherRequest("Fiction", "Teacher", "fictitious", null, "abcdef"),
                    new CreateStudentRequest("Fiction", "Student", "ESTTEST", "abcdef", "3", "A")}) {
                assertThat(validator.validate(dto)).isNotEmpty();
            }
            assertThat(validator.validate(new LoginRequest("legacy", "short"))).isEmpty();
        }
        var env = new MockEnvironment().withProperty("app.bootstrap.admin.username", "fixtureadmin")
                .withProperty("app.bootstrap.admin.password", "passwordpassword");
        assertThatThrownBy(() -> BootstrapAdminSettings.validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("app.bootstrap.admin.password", "Una frase ficticia de bootstrap 🧪");
        BootstrapAdminSettings.validate(env);
        for (int i = 0; i < 100; i++) assertThat(PasswordPolicy.valid(PasswordPolicy.temporary())).isTrue();
    }

    @Test void legacyHashesDoNotApplyNewPolicyAndOverlongBcryptIsSafe() {
        var old = new BCryptPasswordEncoder();
        for (String raw : new String[]{"short", "é".repeat(36), "x".repeat(72)}) {
            String hash = old.encode(raw);
            assertThat(encoder.matches(raw, hash)).isTrue();
            assertThat(encoder.upgradeEncoding(hash)).isTrue();
            String migrated = encoder.upgradeVerified(raw);
            assertThat(encoder.matches(raw, migrated)).isTrue();
        }
        String hash = old.encode("x".repeat(72));
        assertThat(encoder.matches("x".repeat(73), hash)).isFalse();
        assertThat(encoder.matches("é".repeat(37), hash)).isFalse();
        assertThat(encoder.matches("x".repeat(1025), hash)).isFalse();
    }
}
