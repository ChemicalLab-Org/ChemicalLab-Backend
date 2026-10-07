package com.morales.chemicallab.config;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Repository mocks verify branches and zero mutations; PostgreSQL restart coverage is separate. */
class AccountProvisioningTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TeacherProfileRepository teachers = mock(TeacherProfileRepository.class);
    private final StudentProfileRepository students = mock(StudentProfileRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockEnvironment env = new MockEnvironment();

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "prod"})
    void disabledDemosNeverAccessOrMutateRepositories(String profile) {
        env.setActiveProfiles(profile);
        demos().run(null);
        verifyNoInteractions(users, teachers, students, encoder);
    }

    @Test
    void productionCannotCreateDemosEvenWithFlagEnabled() {
        env.setActiveProfiles("prod");
        env.setProperty("app.demo.enabled", "true");
        demos().run(null);
        verifyNoInteractions(users, teachers, students, encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void explicitFlagCreatesOnlyTeacherAndStudent(String profile) {
        env.setActiveProfiles(profile);
        env.setProperty("app.demo.enabled", "true");
        when(encoder.encode(anyString())).thenReturn("hashed");
        demos().run(null);
        var accounts = ArgumentCaptor.forClass(UserAccount.class);
        verify(users, times(2)).save(accounts.capture());
        assertThat(accounts.getAllValues()).extracting(UserAccount::getRole)
                .containsExactly(Role.DOCENTE, Role.ESTUDIANTE);
        assertThat(accounts.getAllValues()).allMatch(UserAccount::getTemporaryPassword);
        verify(teachers).save(any());
        verify(students).save(any());
    }

    @Test
    void enabledDemosPreserveExistingAccountsAndProfiles() {
        env.setActiveProfiles("dev");
        env.setProperty("app.demo.enabled", "true");
        var teacherAccount = UserAccount.builder().username("docente").role(Role.DOCENTE)
                .password("existing-hash").temporaryPassword(false).build();
        var teacher = TeacherProfile.builder().user(teacherAccount).names("Existing").lastNames("History").build();
        when(users.existsByUsername("docente")).thenReturn(true);
        when(users.findByUsername("docente")).thenReturn(Optional.of(teacherAccount));
        when(teachers.findByUser(teacherAccount)).thenReturn(Optional.of(teacher));
        when(users.existsByUsername("EST0001")).thenReturn(true);
        demos().run(null);
        demos().run(null);
        verify(users, never()).save(any());
        verify(teachers, never()).save(any());
        verifyNoInteractions(students, encoder);
        assertThat(teacherAccount.getPassword()).isEqualTo("existing-hash");
        assertThat(teacherAccount.getTemporaryPassword()).isFalse();
        assertThat(teacher.getNames()).isEqualTo("Existing");
    }

    @Test
    void bootstrapIsDisabledWithoutExplicitAction() {
        bootstrap().run(null);
        verifyNoInteractions(users, encoder, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "short", "Admin123*", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void bootstrapRequiresValidExternalCredentials(String password) {
        credentials();
        env.setProperty("app.bootstrap.admin.password", password);
        assertThatThrownBy(() -> bootstrap().run(null)).hasMessageContaining("APP_BOOTSTRAP_ADMIN_PASSWORD");
        verifyNoInteractions(users, encoder, jdbc);
    }

    @Test
    void bootstrapRequiresValidUsernameAndEmail() {
        credentials();
        env.setProperty("app.bootstrap.admin.username", "");
        assertThatThrownBy(() -> bootstrap().run(null)).hasMessageContaining("APP_BOOTSTRAP_ADMIN_USERNAME");
        env.setProperty("app.bootstrap.admin.username", "firstadmin");
        env.setProperty("app.bootstrap.admin.email", "invalid");
        assertThatThrownBy(() -> bootstrap().run(null)).hasMessageContaining("APP_BOOTSTRAP_ADMIN_EMAIL");
        verifyNoInteractions(users, encoder, jdbc);
    }

    @Test
    void bootstrapCreatesOneTemporaryAdminAfterTakingLock() {
        credentials();
        when(encoder.encode("fictitious-password-for-tests")).thenReturn("hash-only");
        bootstrap().run(null);
        var order = inOrder(jdbc, users);
        order.verify(jdbc).execute("SELECT pg_advisory_xact_lock(740101)");
        order.verify(users).countByRole(Role.ADMINISTRADOR);
        var account = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(account.capture());
        assertThat(account.getValue().getPassword()).isEqualTo("hash-only");
        assertThat(account.getValue().getRole()).isEqualTo(Role.ADMINISTRADOR);
        assertThat(account.getValue().getTemporaryPassword()).isTrue();
        verifyNoInteractions(teachers, students);
    }

    @Test
    void anyExistingAdminIncludingInactivePreventsCreationAndPasswordOverwrite() {
        credentials();
        when(users.countByRole(Role.ADMINISTRADOR)).thenReturn(1L);
        bootstrap().run(null);
        bootstrap().run(null);
        verify(users, never()).save(any());
        verifyNoInteractions(encoder, teachers, students);
    }

    @Test
    void bootstrapRejectsOccupiedIdentityWithoutUpdatingIt() {
        credentials();
        when(users.existsByUsername("firstadmin")).thenReturn(true);
        assertThatThrownBy(() -> bootstrap().run(null)).hasMessageContaining("ocupada");
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }

    private void credentials() {
        env.setProperty("app.bootstrap.admin.enabled", "true");
        env.setProperty("app.bootstrap.admin.username", "firstadmin");
        env.setProperty("app.bootstrap.admin.password", "fictitious-password-for-tests");
    }

    private DemoAccountSeeder demos() { return new DemoAccountSeeder(users, teachers, students, encoder, env); }
    private AdminBootstrap bootstrap() { return new AdminBootstrap(users, encoder, env, jdbc); }
}
