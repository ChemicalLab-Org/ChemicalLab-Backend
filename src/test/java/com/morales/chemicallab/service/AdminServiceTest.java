package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMocks
    private AdminService service;

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
                .id(1L).title("Nomenclatura de ácidos").category(ConceptCategory.ACIDOS)
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
}
