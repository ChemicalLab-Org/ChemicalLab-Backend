package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.UpdateStudentRequest;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de gestión de usuarios, centradas en la trazabilidad de
 * la edición de estudiantes por parte del docente. Los repositorios y el servicio de
 * auditoría se mockean; la resolución del actor (docente) y su rol a partir del contexto
 * de seguridad se valida en {@code AuditLogServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserManagementService service;

    private TeacherProfile teacher(Long userId) {
        UserAccount user = UserAccount.builder()
                .id(userId).username("doc" + userId).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder().id(userId).user(user).names("Laura").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code, TeacherProfile teacher) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).temporaryPassword(false).build();
        return StudentProfile.builder()
                .id(id).user(user).teacher(teacher).studentCode(code)
                .names("Ana").lastNames("Mendoza").grade("3").section("A").build();
    }

    @Test
    void updateStudent_registraLogDeAuditoriaSinDatosSensibles() {
        TeacherProfile teacher = teacher(2L);
        StudentProfile student = student(3L, "EST0003", teacher);

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser())).thenReturn(Optional.of(teacher));
        when(studentProfileRepository.findById(3L)).thenReturn(Optional.of(student));

        // Cambian nombres y sección; el resto se conserva.
        UpdateStudentRequest request = new UpdateStudentRequest(
                "Mario", "Mendoza", null, "3", "B", true);

        service.updateStudent(2L, 3L, request);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordInfo(
                eq(LogEventType.USER_UPDATED), eq("UserAccount"), eq(3L),
                eq("EST0003"), eq("Actualizar estudiante"),
                description.capture(), metadata.capture());

        // El evento identifica al estudiante afectado y describe la acción del docente.
        assertThat(description.getValue()).contains("docente").contains("EST0003");
        // Solo se registran nombres de campos modificados (nombres, sección), nunca sus valores.
        assertThat(metadata.getValue())
                .contains("role=ESTUDIANTE").contains("nombres").contains("sección");
        String registrado = (description.getValue() + " " + metadata.getValue()).toLowerCase();
        assertThat(registrado)
                .doesNotContain("password").doesNotContain("contraseña")
                .doesNotContain("temporal").doesNotContain("token")
                .doesNotContain("mario").doesNotContain("ana");
    }

    @Test
    void updateStudent_deOtroDocente_noRegistraLog() {
        TeacherProfile teacher = teacher(2L);
        TeacherProfile otroDocente = teacher(9L);
        StudentProfile student = student(3L, "EST0003", otroDocente);

        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser())).thenReturn(Optional.of(teacher));
        when(studentProfileRepository.findById(3L)).thenReturn(Optional.of(student));

        UpdateStudentRequest request = new UpdateStudentRequest(
                "Mario", "Mendoza", null, "3", "B", true);

        // El estudiante pertenece a otro docente: la edición falla por validación.
        assertThatThrownBy(() -> service.updateStudent(2L, 3L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pertenece al docente");

        // Una edición fallida nunca debe registrarse como exitosa.
        verify(auditLogService, never()).recordInfo(any(), any(), any(), any(), any(), any(), any());
    }
}
