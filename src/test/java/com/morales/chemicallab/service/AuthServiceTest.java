package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.CurrentUserResponse;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.security.JwtService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link AuthService#getCurrentUser()}, que respalda GET /api/auth/me y
 * permite al frontend validar la sesión almacenada. Se mockean los repositorios y se manipula
 * el SecurityContext para simular la autenticación por JWT, sin tocar base de datos.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AuthService authService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
    }

    @Test
    void getCurrentUser_devuelveDatosDelUsuarioAutenticadoSinTokenNiPassword() {
        authenticateAs("admin");
        UserAccount user = UserAccount.builder()
                .id(7L)
                .username("admin")
                .email("admin@chemicallab.test")
                .password("hash-secreto")
                .role(Role.ADMINISTRADOR)
                .active(true)
                .temporaryPassword(false)
                .build();
        when(userAccountRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        CurrentUserResponse response = authService.getCurrentUser();

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.email()).isEqualTo("admin@chemicallab.test");
        assertThat(response.role()).isEqualTo(Role.ADMINISTRADOR);
        assertThat(response.active()).isTrue();
        assertThat(response.temporaryPassword()).isFalse();
        // El administrador no tiene perfil de nombres asociado.
        assertThat(response.names()).isNull();
        assertThat(response.lastNames()).isNull();
    }

    @Test
    void getCurrentUser_sinAutenticacion_lanzaBadCredentials() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void getCurrentUser_conAutenticacionAnonima_lanzaBadCredentials() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void getCurrentUser_cuentaInactiva_lanzaDisabled() {
        authenticateAs("inactivo");
        UserAccount user = UserAccount.builder()
                .id(9L)
                .username("inactivo")
                .role(Role.DOCENTE)
                .active(false)
                .temporaryPassword(false)
                .build();
        when(userAccountRepository.findByUsername("inactivo")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void getCurrentUser_usuarioNoEncontrado_lanzaEntityNotFound() {
        authenticateAs("fantasma");
        when(userAccountRepository.findByUsername("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(EntityNotFoundException.class);
    }
}
