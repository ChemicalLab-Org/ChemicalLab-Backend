package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.StudentUsageRecordDetailResponse;
import com.morales.chemicallab.dto.StudentUsageRecordResponse;
import com.morales.chemicallab.dto.StudentUsageRecordsResponse;
import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAssignment;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.EvaluationAnswerRepository;
import com.morales.chemicallab.repository.EvaluationAssignmentRepository;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UsageEventRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del registro de uso por estudiante. Verifican que cada indicador se
 * calcula sobre su fuente de datos real, que los filtros y validaciones se comportan como
 * el contrato del endpoint (400 ante filtros inválidos, 404 ante usuario inexistente) y
 * que los indicadores no calculables viajan como null en lugar de valores inventados.
 *
 * <p>La restricción de acceso (solo ADMINISTRADOR) se aplica en {@code SecurityConfig}
 * mediante los matchers de {@code /api/admin/**} y el matcher explícito de
 * {@code /api/admin/student-usage-records/**}.</p>
 */
@ExtendWith(MockitoExtension.class)
class StudentUsageRecordServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 1, 10, 0);

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private UsageEventRepository usageEventRepository;
    @Mock
    private SystemLogRepository systemLogRepository;
    @Mock
    private EvaluationAttemptRepository evaluationAttemptRepository;
    @Mock
    private EvaluationAnswerRepository evaluationAnswerRepository;
    @Mock
    private EvaluationAssignmentRepository evaluationAssignmentRepository;

    @InjectMocks
    private StudentUsageRecordService service;

    // =========================================================================
    // CÁLCULO DE INDICADORES
    // =========================================================================

    @Test
    void getRecords_consolidaIndicadoresDelEstudianteConDatosReales() {
        UserAccount user = studentAccount(1L, "e001");
        StudentProfile student = studentProfile(10L, user, "S001", "3", "B");
        Evaluation evalA = evaluation(100L, "Enlaces químicos");
        Evaluation evalB = evaluation(101L, "Tabla periódica");

        when(userAccountRepository.findAll()).thenReturn(List.of(user));
        when(studentProfileRepository.findAll()).thenReturn(List.of(student));
        when(usageEventRepository.findAll(ArgumentMatchers.<Specification<UsageEvent>>any()))
                .thenReturn(List.of(
                        usageEvent(1L, UsageModule.PERIODIC_TABLE, NOW.minusDays(2)),
                        usageEvent(1L, UsageModule.PERIODIC_TABLE, NOW.minusDays(1)),
                        usageEvent(1L, UsageModule.EVALUATIONS, NOW)));
        when(systemLogRepository.findAll(ArgumentMatchers.<Specification<SystemLog>>any()))
                .thenReturn(List.of(
                        login(1L, NOW.minusDays(3)),
                        login(1L, NOW.minusDays(1)),
                        incident(1L, LogSeverity.ERROR, NOW.minusDays(1))));
        when(evaluationAttemptRepository.findAll()).thenReturn(List.of(
                attempt(student, evalA, AttemptStatus.GRADED, NOW.minusDays(1), "Buen trabajo"),
                attempt(student, evalA, AttemptStatus.IN_PROGRESS, null, null),
                attempt(student, evalB, AttemptStatus.IN_PROGRESS, null, null)));
        when(evaluationAnswerRepository.findCorrectnessStats()).thenReturn(List.of(
                correctness(10L, true, NOW.minusDays(1)),
                correctness(10L, true, NOW.minusDays(1)),
                correctness(10L, true, NOW.minusDays(1)),
                correctness(10L, false, NOW.minusDays(1))));
        when(evaluationAnswerRepository.findTeacherFeedbackStats()).thenReturn(List.of(
                feedback(10L, NOW.minusDays(1))));
        when(evaluationAssignmentRepository.findAll()).thenReturn(List.of(
                assignment(evalA, "3", "B"),
                assignment(evalB, "3", "B")));

        StudentUsageRecordsResponse response = service.getRecords(
                null, null, null, null, null, null, null, null);

        assertThat(response.records()).hasSize(1);
        StudentUsageRecordResponse record = response.records().get(0);
        assertThat(record.code()).isEqualTo("S001");
        assertThat(record.fullName()).isEqualTo("Ana Pérez");
        assertThat(record.sessionsStarted()).isEqualTo(2L);
        assertThat(record.visitedModulesCount()).isEqualTo(2);
        assertThat(record.visitedModules()).containsExactly("EVALUATIONS", "PERIODIC_TABLE");
        assertThat(record.assignedActivities()).isEqualTo(2L);
        assertThat(record.completedActivities()).isEqualTo(1L);
        assertThat(record.progressPercentage()).isEqualTo(50.0);
        assertThat(record.attemptsCount()).isEqualTo(3L);
        assertThat(record.correctAnswers()).isEqualTo(3L);
        assertThat(record.incorrectAnswers()).isEqualTo(1L);
        assertThat(record.accuracyRate()).isEqualTo(75.0);
        assertThat(record.feedbackReceived()).isEqualTo(2L); // 1 general + 1 por respuesta
        assertThat(record.technicalIncidentsCount()).isEqualTo(1L);
        assertThat(record.technicalIncidentsSummary()).isEqualTo("1 error");
        assertThat(record.lastActivityAt()).isEqualTo(NOW);

        // Sin registro confiable de duración de sesión: nunca se inventa el tiempo de uso.
        assertThat(record.totalUsageMinutes()).isNull();

        assertThat(response.summary().totalUsers()).isEqualTo(1);
        assertThat(response.summary().studentsWithActivity()).isEqualTo(1);
        assertThat(response.summary().averageProgress()).isEqualTo(50.0);
        assertThat(response.summary().averageAccuracy()).isEqualTo(75.0);
        assertThat(response.summary().totalSessionsStarted()).isEqualTo(2);
        assertThat(response.summary().topModule()).isEqualTo("PERIODIC_TABLE");
        assertThat(response.summary().topModuleCount()).isEqualTo(2L);
    }

    @Test
    void getRecords_estudianteSinIntentosNiAsignaciones_indicadoresNulosNoInventados() {
        UserAccount user = studentAccount(1L, "e001");
        StudentProfile student = studentProfile(10L, user, "S001", "3", "B");

        when(userAccountRepository.findAll()).thenReturn(List.of(user));
        when(studentProfileRepository.findAll()).thenReturn(List.of(student));

        StudentUsageRecordsResponse response = service.getRecords(
                null, null, null, null, null, null, null, null);

        StudentUsageRecordResponse record = response.records().get(0);
        assertThat(record.sessionsStarted()).isZero();
        assertThat(record.attemptsCount()).isZero();
        assertThat(record.assignedActivities()).isZero();
        assertThat(record.progressPercentage()).isNull();   // sin asignaciones no hay avance calculable
        assertThat(record.accuracyRate()).isNull();          // sin respuestas corregidas no hay tasa
        assertThat(record.totalUsageMinutes()).isNull();
        assertThat(record.technicalIncidentsSummary()).isNull();
        assertThat(record.lastActivityAt()).isNull();

        assertThat(response.summary().averageProgress()).isNull();
        assertThat(response.summary().averageAccuracy()).isNull();
        assertThat(response.summary().topModule()).isNull();
    }

    @Test
    void getRecords_docente_noRecibeIndicadoresDeEvaluacionInventados() {
        UserAccount docente = account(2L, "prof01", Role.DOCENTE);
        TeacherProfile teacher = teacherProfile(20L, docente);

        when(userAccountRepository.findAll()).thenReturn(List.of(docente));
        when(teacherProfileRepository.findAll()).thenReturn(List.of(teacher));
        when(usageEventRepository.findAll(ArgumentMatchers.<Specification<UsageEvent>>any()))
                .thenReturn(List.of(usageEvent(2L, UsageModule.WHITEBOARD, NOW)));
        when(systemLogRepository.findAll(ArgumentMatchers.<Specification<SystemLog>>any()))
                .thenReturn(List.of(login(2L, NOW)));

        StudentUsageRecordsResponse response = service.getRecords(
                "DOCENTE", null, null, null, null, null, null, null);

        StudentUsageRecordResponse record = response.records().get(0);
        assertThat(record.role()).isEqualTo(Role.DOCENTE);
        assertThat(record.fullName()).isEqualTo("Luis Gómez");
        assertThat(record.grade()).isNull();
        assertThat(record.section()).isNull();
        assertThat(record.sessionsStarted()).isEqualTo(1L);
        assertThat(record.visitedModulesCount()).isEqualTo(1);
        // Indicadores de evaluación: no aplican al docente, viajan como null.
        assertThat(record.assignedActivities()).isNull();
        assertThat(record.completedActivities()).isNull();
        assertThat(record.progressPercentage()).isNull();
        assertThat(record.attemptsCount()).isNull();
        assertThat(record.correctAnswers()).isNull();
        assertThat(record.incorrectAnswers()).isNull();
        assertThat(record.accuracyRate()).isNull();
        assertThat(record.feedbackReceived()).isNull();
    }

    // =========================================================================
    // FILTROS
    // =========================================================================

    @Test
    void getRecords_filtraPorBusquedaPorCodigoDeEstudiante() {
        UserAccount user1 = studentAccount(1L, "e001");
        UserAccount user2 = studentAccount(2L, "e002");
        StudentProfile s1 = studentProfile(10L, user1, "S001", "3", "B");
        StudentProfile s2 = studentProfile(11L, user2, "S002", "3", "B");
        s2.setNames("María");
        s2.setLastNames("Quispe");

        when(userAccountRepository.findAll()).thenReturn(List.of(user1, user2));
        when(studentProfileRepository.findAll()).thenReturn(List.of(s1, s2));

        StudentUsageRecordsResponse response = service.getRecords(
                null, "s002", null, null, null, null, null, null);

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).code()).isEqualTo("S002");
    }

    @Test
    void getRecords_filtraPorGradoYSeccion_excluyeNoEstudiantes() {
        UserAccount user1 = studentAccount(1L, "e001");
        UserAccount user2 = studentAccount(2L, "e002");
        UserAccount admin = account(3L, "admin", Role.ADMINISTRADOR);
        StudentProfile s1 = studentProfile(10L, user1, "S001", "3", "B");
        StudentProfile s2 = studentProfile(11L, user2, "S002", "4", "A");

        when(userAccountRepository.findAll()).thenReturn(List.of(user1, user2, admin));
        when(studentProfileRepository.findAll()).thenReturn(List.of(s1, s2));

        StudentUsageRecordsResponse response = service.getRecords(
                null, null, "4", "a", null, null, null, null);

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).code()).isEqualTo("S002");
        assertThat(response.records().get(0).grade()).isEqualTo("4");
    }

    @Test
    void getRecords_soloEstudiantesConActividad_excluyeInactivos() {
        UserAccount conActividad = studentAccount(1L, "e001");
        UserAccount sinActividad = studentAccount(2L, "e002");
        StudentProfile s1 = studentProfile(10L, conActividad, "S001", "3", "B");
        StudentProfile s2 = studentProfile(11L, sinActividad, "S002", "3", "B");

        when(userAccountRepository.findAll()).thenReturn(List.of(conActividad, sinActividad));
        when(studentProfileRepository.findAll()).thenReturn(List.of(s1, s2));
        when(usageEventRepository.findAll(ArgumentMatchers.<Specification<UsageEvent>>any()))
                .thenReturn(List.of(usageEvent(1L, UsageModule.DASHBOARD, NOW)));

        StudentUsageRecordsResponse response = service.getRecords(
                null, null, null, null, null, null, null, "true");

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).code()).isEqualTo("S001");
    }

    // =========================================================================
    // VALIDACIÓN DE FILTROS (contrato 400)
    // =========================================================================

    @Test
    void getRecords_filtrosInvalidos_lanzanIllegalArgument() {
        assertThatThrownBy(() -> service.getRecords(null, null, "7", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grado");

        assertThatThrownBy(() -> service.getRecords("SUPERUSUARIO", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rol");

        assertThatThrownBy(() -> service.getRecords(null, null, null, "AB", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sección");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, "2026-13-40", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desde");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, "2026-07-05", "2026-07-01", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");

        assertThatThrownBy(() -> service.getRecords(null, null, null, null, null, null, "MODULO_FALSO", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("módulo");
    }

    // =========================================================================
    // DETALLE
    // =========================================================================

    @Test
    void getDetail_usuarioInexistente_lanzaNotFound() {
        when(userAccountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getDetail_devuelveResumenEventosYEvaluacionesSinDatosSensibles() {
        UserAccount user = studentAccount(1L, "e001");
        StudentProfile student = studentProfile(10L, user, "S001", "3", "B");
        Evaluation evalA = evaluation(100L, "Enlaces químicos");
        UsageEvent event = usageEvent(1L, UsageModule.EVALUATIONS, NOW);
        event.setEventType(UsageEventType.EVALUATION_OPENED);
        event.setDescription("Abrió la evaluación");

        when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));
        when(studentProfileRepository.findAll()).thenReturn(List.of(student));
        when(usageEventRepository.findAll(ArgumentMatchers.<Specification<UsageEvent>>any()))
                .thenReturn(List.of(event));
        when(usageEventRepository.findAll(ArgumentMatchers.<Specification<UsageEvent>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(evaluationAttemptRepository.findAll()).thenReturn(List.of(
                attempt(student, evalA, AttemptStatus.GRADED, NOW, null)));
        when(evaluationAssignmentRepository.findAll()).thenReturn(List.of(
                assignment(evalA, "3", "B")));
        when(systemLogRepository.findAll(ArgumentMatchers.<Specification<SystemLog>>any()))
                .thenReturn(List.of(incident(1L, LogSeverity.WARNING, NOW)));

        StudentUsageRecordDetailResponse detail = service.getDetail(1L);

        assertThat(detail.summary().code()).isEqualTo("S001");
        assertThat(detail.summary().totalUsageMinutes()).isNull();

        assertThat(detail.recentEvents()).hasSize(1);
        assertThat(detail.recentEvents().get(0).module()).isEqualTo(UsageModule.EVALUATIONS);

        assertThat(detail.evaluations()).hasSize(1);
        StudentUsageRecordDetailResponse.EvaluationUsageItem item = detail.evaluations().get(0);
        assertThat(item.title()).isEqualTo("Enlaces químicos");
        assertThat(item.assigned()).isTrue();
        assertThat(item.completed()).isTrue();
        assertThat(item.attemptsCount()).isEqualTo(1);

        assertThat(detail.incidents()).hasSize(1);
        assertThat(detail.incidents().get(0).severity()).isEqualTo(LogSeverity.WARNING);
    }

    // =========================================================================
    // FÁBRICAS DE DATOS DE PRUEBA
    // =========================================================================

    private UserAccount account(Long id, String username, Role role) {
        return UserAccount.builder().id(id).username(username).role(role).active(true).build();
    }

    private UserAccount studentAccount(Long id, String username) {
        return account(id, username, Role.ESTUDIANTE);
    }

    private StudentProfile studentProfile(Long id, UserAccount user, String code, String grade, String section) {
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code)
                .names("Ana").lastNames("Pérez")
                .grade(grade).section(section)
                .build();
    }

    private TeacherProfile teacherProfile(Long id, UserAccount user) {
        return TeacherProfile.builder().id(id).user(user).names("Luis").lastNames("Gómez").build();
    }

    private UsageEvent usageEvent(Long userId, UsageModule module, LocalDateTime occurredAt) {
        return UsageEvent.builder()
                .userId(userId).username("u" + userId).userRole(Role.ESTUDIANTE)
                .module(module).eventType(UsageEventType.MODULE_ACCESS)
                .occurredAt(occurredAt)
                .build();
    }

    private SystemLog login(Long userId, LocalDateTime createdAt) {
        return SystemLog.builder()
                .eventType(LogEventType.LOGIN_SUCCESS)
                .category(LogEventType.LOGIN_SUCCESS.getDefaultCategory())
                .severity(LogSeverity.INFO)
                .actorUserId(userId)
                .createdAt(createdAt)
                .build();
    }

    private SystemLog incident(Long userId, LogSeverity severity, LocalDateTime createdAt) {
        return SystemLog.builder()
                .eventType(LogEventType.ADMIN_ACTION)
                .category(LogEventType.ADMIN_ACTION.getDefaultCategory())
                .severity(severity)
                .actorUserId(userId)
                .createdAt(createdAt)
                .build();
    }

    private Evaluation evaluation(Long id, String title) {
        return Evaluation.builder()
                .id(id).title(title).status(EvaluationStatus.PUBLISHED).active(true)
                .build();
    }

    private EvaluationAssignment assignment(Evaluation evaluation, String grade, String section) {
        return EvaluationAssignment.builder()
                .evaluation(evaluation).grade(grade).section(section).active(true)
                .build();
    }

    private EvaluationAttempt attempt(StudentProfile student, Evaluation evaluation,
                                      AttemptStatus status, LocalDateTime submittedAt,
                                      String overallFeedback) {
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .evaluation(evaluation).student(student).status(status)
                .submittedAt(submittedAt).overallFeedback(overallFeedback)
                .build();
        attempt.setStartedAt(submittedAt != null ? submittedAt.minusMinutes(30) : NOW.minusDays(1));
        return attempt;
    }

    private EvaluationAnswerRepository.AnswerCorrectnessView correctness(
            Long studentId, Boolean correct, LocalDateTime answeredAt) {
        return new EvaluationAnswerRepository.AnswerCorrectnessView() {
            @Override
            public Long getStudentId() {
                return studentId;
            }

            @Override
            public Boolean getCorrect() {
                return correct;
            }

            @Override
            public LocalDateTime getAnsweredAt() {
                return answeredAt;
            }
        };
    }

    private EvaluationAnswerRepository.AnswerFeedbackView feedback(Long studentId, LocalDateTime reviewedAt) {
        return new EvaluationAnswerRepository.AnswerFeedbackView() {
            @Override
            public Long getStudentId() {
                return studentId;
            }

            @Override
            public LocalDateTime getReviewedAt() {
                return reviewedAt;
            }
        };
    }
}
