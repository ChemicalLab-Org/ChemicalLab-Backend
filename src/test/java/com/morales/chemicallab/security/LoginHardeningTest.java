package com.morales.chemicallab.security;

import com.morales.chemicallab.config.SecurityConfig;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.exception.GlobalExceptionHandler;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class LoginHardeningTest {
    @Test void publicFailuresAreIdenticalAndAlwaysCheckPassword() throws Exception {
        var users = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var limits = mock(AuthenticationLimits.class);
        when(limits.login(any())).thenAnswer(call -> {
            String identifier = ((LoginRequest)call.getArgument(0)).usernameOrEmail();
            return new AuthenticationLimits.LoginBudget(identifier, identifier.startsWith("absent") ? null : 1L, true);
        });
        var service = new AuthService(users, mock(TeacherProfileRepository.class), mock(StudentProfileRepository.class),
                encoder, mock(AccountSessionService.class), mock(AuditLogService.class));
        var mvc = MockMvcBuilders.standaloneSetup(new com.morales.chemicallab.controller.AuthController(service, mock(com.morales.chemicallab.security.audit.AuditRejections.class), limits))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String expected = null;
        for (String identifier : new String[]{"absent", "absent@example.test", "active", "active@example.test", "inactive", "inactive@example.test"}) {
            if (!identifier.startsWith("absent")) {
                var user = UserAccount.builder().id(1L).username(identifier.split("@")[0])
                        .email(identifier.split("@")[0] + "@example.test").password("hash")
                        .role(Role.ESTUDIANTE).active(identifier.startsWith("active")).build();
                if (identifier.contains("@")) when(users.findByEmail(identifier)).thenReturn(Optional.of(user));
                else when(users.findByUsername(identifier)).thenReturn(Optional.of(user));
            }
            var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                    .content("{\"usernameOrEmail\":\"" + identifier + "\",\"password\":\"wrong-password\"}"))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(401);
            String body = response.getContentAsString().replaceAll("\"timestamp\":\"[^\"]+\",?", "");
            if (expected == null) expected = body;
            assertThat(body).isEqualTo(expected);
        }
        verify(encoder, times(6)).matches(eq("wrong-password"), any());
    }

    @Test void concealedBudgetAlwaysChecksDummyAndNeverIssuesOrMigrates() {
        var users = mock(UserAccountRepository.class);
        var encoder = mock(CompatiblePasswordEncoder.class);
        var sessions = mock(AccountSessionService.class);
        var service = new AuthService(users, mock(TeacherProfileRepository.class), mock(StudentProfileRepository.class),
                encoder, sessions, mock(AuditLogService.class));
        var user = UserAccount.builder().id(1L).username("active").password("real-hash").active(true).build();
        when(users.findByUsername("active")).thenReturn(Optional.of(user));
        when(encoder.matches("correct-secret", null)).thenReturn(true); // cannot bypass the decision
        for (String identifier : new String[]{"active", "absent"}) {
            assertThatThrownBy(() -> service.login(new LoginRequest(identifier,"correct-secret"),
                    new AuthenticationLimits.LoginBudget(identifier, identifier.equals("active") ? 1L : null, false)))
                    .isInstanceOf(com.morales.chemicallab.exception.LoginRejectedException.class);
        }
        assertThatThrownBy(() -> service.login(new LoginRequest("active","correct-secret"),
                new AuthenticationLimits.LoginBudget("active",999L,true)))
                .isInstanceOf(com.morales.chemicallab.exception.LoginRejectedException.class);
        verify(encoder, times(3)).matches("correct-secret", null);
        verify(encoder, never()).matches(any(), eq("real-hash"));
        verify(encoder, never()).upgradeVerified(any());
        verify(sessions, never()).issue(any());
        verify(sessions, never()).lock(any());
        assertThat(user.getPassword()).isEqualTo("real-hash");
    }

    @Test void newPolicyRejectsSixCharacters() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new ChangePasswordRequest("old", "abcdef", "abcdef"))).isNotEmpty();
        }
    }

    @Test void encoderAcceptsLongUnicodePhrases() {
        var encoder = new SecurityConfig(null, null, null, null, null, null, null).passwordEncoder();
        String phrase = "🧪 Química y cielos abiertos ".repeat(4);
        String hash = encoder.encode(phrase);
        assertThat(encoder.matches(phrase, hash)).isTrue();
    }
}
