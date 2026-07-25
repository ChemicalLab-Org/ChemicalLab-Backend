package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserCreatedResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.dto.CreateUserRequest;
import com.morales.chemicallab.dto.UpdateUserRequest;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de apoyo al panel administrativo. Se mockean los
 * repositorios para validar el cálculo de métricas, el listado unificado de usuarios
 * y la actividad reciente, sin tocar base de datos.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private ConceptContentRepository conceptContentRepository;
    @Mock
    private EvaluationRepository evaluationRepository;
    @Mock
    private EvaluationAttemptRepository evaluationAttemptRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AdminService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, UserAccount account) {
        when(userAccountRepository.findByUsername(username)).thenReturn(Optional.of(account));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    // =========================================================================
    // Datos de apoyo
    // =========================================================================

    private UserAccount account(Long id, String username, Role role, boolean active) {
        return UserAccount.builder()
                .id(id).username(username).email(username + "@correo.com")
                .role(role).active(active).temporaryPassword(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private TeacherProfile teacher(Long userId, String names, String lastNames) {
        UserAccount user = account(userId, "doc" + userId, Role.DOCENTE, true);
        return TeacherProfile.builder().id(userId).user(user).names(names).lastNames(lastNames).build();
    }

    private StudentProfile student(Long userId, String code, String names, String lastNames) {
        UserAccount user = account(userId, code, Role.ESTUDIANTE, true);
        return StudentProfile.builder()
                .id(userId).user(user).studentCode(code).names(names).lastNames(lastNames)
                .grade("1").section("A").build();
    }

    // =========================================================================
    // RESUMEN
    // =========================================================================

    @Test
    void getSummary_agregaConteosDeUsuariosYModulos() {
        when(userAccountRepository.countByRole(Role.ADMINISTRADOR)).thenReturn(1L);
        when(userAccountRepository.countByRole(Role.DOCENTE)).thenReturn(3L);
        when(userAccountRepository.countByRole(Role.ESTUDIANTE)).thenReturn(10L);
        when(userAccountRepository.countByActive(true)).thenReturn(12L);
        when(userAccountRepository.countByActive(false)).thenReturn(2L);
        when(userAccountRepository.countByRoleAndActive(Role.DOCENTE, true)).thenReturn(2L);
        when(userAccountRepository.countByRoleAndActive(Role.ESTUDIANTE, true)).thenReturn(9L);

        when(conceptContentRepository.count()).thenReturn(5L);
        when(conceptContentRepository.countByStatus(ConceptStatus.PUBLISHED)).thenReturn(4L);
        when(evaluationRepository.count()).thenReturn(7L);
        when(evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED)).thenReturn(6L);
        when(evaluationAttemptRepository.countByStatusIn(any())).thenReturn(15L);

        AdminSummaryResponse summary = service.getSummary();

        assertThat(summary.totalAdmins()).isEqualTo(1L);
        assertThat(summary.totalTeachers()).isEqualTo(3L);
        assertThat(summary.totalStudents()).isEqualTo(10L);
        assertThat(summary.totalUsers()).isEqualTo(14L);
        assertThat(summary.activeUsers()).isEqualTo(12L);
        assertThat(summary.inactiveUsers()).isEqualTo(2L);
        assertThat(summary.activeTeachers()).isEqualTo(2L);
        assertThat(summary.activeStudents()).isEqualTo(9L);
        assertThat(summary.totalConcepts()).isEqualTo(5L);
        assertThat(summary.publishedConcepts()).isEqualTo(4L);
        assertThat(summary.totalEvaluations()).isEqualTo(7L);
        assertThat(summary.publishedEvaluations()).isEqualTo(6L);
        assertThat(summary.submittedAttempts()).isEqualTo(15L);
    }

    // =========================================================================
    // LISTADO UNIFICADO DE USUARIOS
    // =========================================================================

    @Test
    void listUsers_combinaPerfilesYNoExponeContrasena() {
        UserAccount admin = account(1L, "admin", Role.ADMINISTRADOR, true);
        TeacherProfile teacher = teacher(2L, "Pedro", "Martínez");
        StudentProfile studentProfile = student(3L, "EST0001", "Ana", "Mendoza");

        when(userAccountRepository.findAll())
                .thenReturn(List.of(admin, teacher.getUser(), studentProfile.getUser()));
        when(teacherProfileRepository.findAll()).thenReturn(List.of(teacher));
        when(studentProfileRepository.findAll()).thenReturn(List.of(studentProfile));

        List<AdminUserResponse> users = service.listUsers();

        assertThat(users).hasSize(3);

        AdminUserResponse docente = users.stream()
                .filter(u -> u.role() == Role.DOCENTE).findFirst().orElseThrow();
        assertThat(docente.fullName()).isEqualTo("Pedro Martínez");
        assertThat(docente.code()).isNull();

        AdminUserResponse estudiante = users.stream()
                .filter(u -> u.role() == Role.ESTUDIANTE).findFirst().orElseThrow();
        assertThat(estudiante.fullName()).isEqualTo("Ana Mendoza");
        assertThat(estudiante.code()).isEqualTo("EST0001");

        AdminUserResponse administrador = users.stream()
                .filter(u -> u.role() == Role.ADMINISTRADOR).findFirst().orElseThrow();
        assertThat(administrador.username()).isEqualTo("admin");
    }

    // =========================================================================
    // ACTIVIDAD RECIENTE
    // =========================================================================

    @Test
    void getActivity_armaListasDeRegistrosRecientes() {
        UserAccount user = account(5L, "nuevo.docente", Role.DOCENTE, true);
        when(userAccountRepository.findTop8ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

        TeacherProfile owner = teacher(2L, "Laura", "Quispe");
        Evaluation evaluation = Evaluation.builder()
                .id(1L).title("Evaluación de óxidos").createdByTeacher(owner)
                .createdAt(LocalDateTime.now()).build();
        when(evaluationRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of(evaluation));

        ConceptContent concept = ConceptContent.builder()
                .id(1L).title("Nomenclatura de ácidos").category("Ácidos")
                .explanation("...").createdByTeacher(owner).createdAt(LocalDateTime.now()).build();
        when(conceptContentRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of(concept));

        AdminActivityResponse activity = service.getActivity();

        assertThat(activity.recentUsers()).hasSize(1);
        assertThat(activity.recentUsers().get(0).title()).isEqualTo("nuevo.docente");
        assertThat(activity.recentUsers().get(0).subtitle()).isEqualTo("Docente");

        assertThat(activity.recentEvaluations()).hasSize(1);
        assertThat(activity.recentEvaluations().get(0).title()).isEqualTo("Evaluación de óxidos");
        assertThat(activity.recentEvaluations().get(0).subtitle()).isEqualTo("Laura Quispe");

        assertThat(activity.recentConcepts()).hasSize(1);
        assertThat(activity.recentConcepts().get(0).title()).isEqualTo("Nomenclatura de ácidos");
        assertThat(activity.recentConcepts().get(0).subtitle()).isEqualTo("Laura Quispe");
    }

    // =========================================================================
    // RESTABLECIMIENTO DE CONTRASEÑA
    // =========================================================================

    @Test
    void resetUserPassword_funcionaParaEstudianteSinCorreo() {
        // Estudiante creado por un docente: sin correo registrado.
        UserAccount studentAccount = account(7L, "EST0007", Role.ESTUDIANTE, true);
        studentAccount.setEmail(null);
        studentAccount.setTemporaryPassword(false);

        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(studentAccount));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        AdminPasswordResetResponse response = service.resetUserPassword(7L);

        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(response.message()).contains("restablecida");

        // La cuenta queda con la contraseña cifrada y marcada como temporal.
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("HASH");
        assertThat(saved.getValue().getTemporaryPassword()).isTrue();
    }

    // =========================================================================
    // CREACIÓN DE USUARIOS
    // =========================================================================

    @Test
    void createUser_administrador_generaContrasenaTemporalYNoExponeHash() {
        when(userAccountRepository.existsByUsername("nuevoadmin")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        CreateUserRequest request = new CreateUserRequest(
                Role.ADMINISTRADOR, null, null, "nuevoadmin", null, null, null, null, null);

        AdminUserCreatedResponse response = service.createUser(request);

        assertThat(response.user().role()).isEqualTo(Role.ADMINISTRADOR);
        assertThat(response.user().username()).isEqualTo("nuevoadmin");
        assertThat(response.temporaryPassword()).isNotBlank();

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(saved.capture());
        // La respuesta nunca devuelve el hash y la cuenta nace como temporal.
        assertThat(saved.getValue().getPassword()).isEqualTo("HASH");
        assertThat(response.temporaryPassword()).isNotEqualTo("HASH");
        assertThat(saved.getValue().getTemporaryPassword()).isTrue();
    }

    @Test
    void createUser_docente_creaCuentaYPerfil() {
        when(userAccountRepository.existsByUsername("pedrom")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(teacherProfileRepository.findByUser(any()))
                .thenReturn(Optional.of(teacher(2L, "Pedro", "Martínez")));

        CreateUserRequest request = new CreateUserRequest(
                Role.DOCENTE, "Pedro", "Martínez", "pedrom", null, null, null, null, null);

        AdminUserCreatedResponse response = service.createUser(request);

        assertThat(response.user().role()).isEqualTo(Role.DOCENTE);
        verify(teacherProfileRepository).save(any(TeacherProfile.class));
    }

    @Test
    void createUser_usernameDuplicado_lanzaError() {
        when(userAccountRepository.existsByUsername("repetido")).thenReturn(true);

        CreateUserRequest request = new CreateUserRequest(
                Role.DOCENTE, "Ana", "López", "repetido", null, null, null, null, null);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nombre de usuario ya está registrado");
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    void createUser_estudiante_conDocenteActivo_creaCuentaYPerfil() {
        UserAccount teacherUser = account(2L, "doc2", Role.DOCENTE, true);
        TeacherProfile teacherProfile = TeacherProfile.builder()
                .id(2L).user(teacherUser).names("Laura").lastNames("Quispe").build();

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(teacherUser));
        when(teacherProfileRepository.findByUser(teacherUser)).thenReturn(Optional.of(teacherProfile));
        when(studentProfileRepository.existsByStudentCode("EST0009")).thenReturn(false);
        when(userAccountRepository.existsByUsername("EST0009")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(studentProfileRepository.findByUser_Id(any()))
                .thenReturn(Optional.of(student(3L, "EST0009", "Mario", "Soto")));

        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "5", "A", "EST0009", 2L);

        AdminUserCreatedResponse response = service.createUser(request);

        assertThat(response.user().role()).isEqualTo(Role.ESTUDIANTE);
        verify(studentProfileRepository).save(any(StudentProfile.class));
    }

    @Test
    void createUser_estudiante_conDocenteInactivo_lanzaError() {
        UserAccount teacherUser = account(2L, "doc2", Role.DOCENTE, false);
        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(teacherUser));

        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "5", "A", null, 2L);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no está activo");
        verify(studentProfileRepository, never()).save(any());
    }

    @Test
    void createUser_estudiante_gradoFueraDeRango_lanzaError() {
        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "7", "A", null, 2L);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("del 1 al 5");
        verify(studentProfileRepository, never()).save(any());
    }

    @Test
    void createUser_estudiante_seccionCompuesta_lanzaError() {
        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "3", "3 C", null, 2L);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("una sola letra");
        verify(studentProfileRepository, never()).save(any());
    }

    @Test
    void createUser_estudiante_normalizaSeccionAMayuscula() {
        UserAccount teacherUser = account(2L, "doc2", Role.DOCENTE, true);
        TeacherProfile teacherProfile = TeacherProfile.builder()
                .id(2L).user(teacherUser).names("Laura").lastNames("Quispe").build();

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(teacherUser));
        when(teacherProfileRepository.findByUser(teacherUser)).thenReturn(Optional.of(teacherProfile));
        when(studentProfileRepository.existsByStudentCode("EST0010")).thenReturn(false);
        when(userAccountRepository.existsByUsername("EST0010")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(studentProfileRepository.findByUser_Id(any()))
                .thenReturn(Optional.of(student(3L, "EST0010", "Mario", "Soto")));

        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "3", "c", "EST0010", 2L);

        service.createUser(request);

        ArgumentCaptor<StudentProfile> saved = ArgumentCaptor.forClass(StudentProfile.class);
        verify(studentProfileRepository).save(saved.capture());
        assertThat(saved.getValue().getSection()).isEqualTo("C");
        assertThat(saved.getValue().getGrade()).isEqualTo("3");
    }

    @Test
    void createUser_estudiante_sinDocente_lanzaError() {
        CreateUserRequest request = new CreateUserRequest(
                Role.ESTUDIANTE, "Mario", "Soto", null, null, "5", "A", null, null);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docente responsable");
    }

    // =========================================================================
    // ACTUALIZACIÓN
    // =========================================================================

    @Test
    void updateUser_docente_actualizaNombresDelPerfil() {
        UserAccount user = account(2L, "doc2", Role.DOCENTE, true);
        TeacherProfile teacherProfile = TeacherProfile.builder()
                .id(2L).user(user).names("Viejo").lastNames("Nombre").build();

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(user));
        when(teacherProfileRepository.findByUser(user)).thenReturn(Optional.of(teacherProfile));

        UpdateUserRequest request = new UpdateUserRequest(
                "Nuevo", "Apellido", null, null, null, null);

        AdminUserResponse response = service.updateUser(2L, request);

        assertThat(response.fullName()).isEqualTo("Nuevo Apellido");
        assertThat(teacherProfile.getNames()).isEqualTo("Nuevo");
    }

    @Test
    void updateUser_registraLogDeAuditoriaSinDatosSensibles() {
        UserAccount user = account(2L, "doc2", Role.DOCENTE, true);
        TeacherProfile teacherProfile = TeacherProfile.builder()
                .id(2L).user(user).names("Viejo").lastNames("Apellido").build();

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(user));
        when(teacherProfileRepository.findByUser(user)).thenReturn(Optional.of(teacherProfile));

        UpdateUserRequest request = new UpdateUserRequest(
                "Nuevo", "Apellido", null, null, null, null);

        service.updateUser(2L, request);

        // El administrador edita un docente: debe registrarse un evento USER_UPDATED que
        // identifique al usuario afectado y al rol, sin exponer ningún dato sensible.
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordInfo(
                eq(LogEventType.USER_UPDATED), eq("UserAccount"), eq(2L),
                eq("doc2"), eq("Actualizar usuario"),
                description.capture(), metadata.capture());

        assertThat(description.getValue()).contains("doc2");
        // Solo se registran nombres de campos modificados (nombres), no sus valores.
        assertThat(metadata.getValue()).contains("role=DOCENTE").contains("nombres");
        String registrado = (description.getValue() + " " + metadata.getValue()).toLowerCase();
        assertThat(registrado)
                .doesNotContain("password").doesNotContain("contraseña")
                .doesNotContain("temporal").doesNotContain("token")
                .doesNotContain("nuevo").doesNotContain("viejo");
    }

    @Test
    void updateUser_usuarioInexistente_noRegistraLog() {
        when(userAccountRepository.findById(99L)).thenReturn(Optional.empty());

        UpdateUserRequest request = new UpdateUserRequest(
                "Nuevo", "Apellido", null, null, null, null);

        assertThatThrownBy(() -> service.updateUser(99L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuario no encontrado");

        // Una edición fallida nunca debe registrarse como exitosa.
        verify(auditLogService, never()).recordInfo(any(), any(), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // ACTIVACIÓN / DESACTIVACIÓN
    // =========================================================================

    @Test
    void deactivateUser_propiaCuenta_lanzaError() {
        UserAccount admin = account(1L, "admin", Role.ADMINISTRADOR, true);
        when(userAccountRepository.findById(1L)).thenReturn(Optional.of(admin));
        authenticateAs("admin", admin);

        assertThatThrownBy(() -> service.deactivateUser(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("su propia cuenta");
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    void deactivateUser_ultimoAdministradorActivo_lanzaError() {
        UserAccount admin = account(1L, "admin", Role.ADMINISTRADOR, true);
        when(userAccountRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userAccountRepository.countByRoleAndActive(Role.ADMINISTRADOR, true)).thenReturn(1L);

        assertThatThrownBy(() -> service.deactivateUser(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("último administrador activo");
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    void deactivateUser_docente_desactivaCorrectamente() {
        UserAccount user = account(2L, "doc2", Role.DOCENTE, true);
        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(user));
        when(teacherProfileRepository.findByUser(user))
                .thenReturn(Optional.of(teacher(2L, "Pedro", "Martínez")));

        AdminUserResponse response = service.deactivateUser(2L);

        assertThat(response.active()).isFalse();
        verify(userAccountRepository).save(user);
    }
}
