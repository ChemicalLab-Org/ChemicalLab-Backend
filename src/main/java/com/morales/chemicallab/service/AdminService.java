package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AdminActivityItem;
import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio de apoyo al panel administrativo. Calcula métricas generales y arma una
 * vista de actividad reciente exclusivamente a partir de los registros existentes.
 *
 * <p>Es de solo lectura: no crea, modifica ni elimina datos, y nunca expone
 * contraseñas. La trazabilidad detallada (logs/auditoría) queda fuera de este
 * servicio y se abordará en su propio módulo.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final ConceptContentRepository conceptContentRepository;
    private final EvaluationRepository evaluationRepository;
    private final EvaluationAttemptRepository evaluationAttemptRepository;

    // =========================================================================
    // RESUMEN / MÉTRICAS
    // =========================================================================

    public AdminSummaryResponse getSummary() {
        long totalAdmins = userAccountRepository.countByRole(Role.ADMINISTRADOR);
        long totalTeachers = userAccountRepository.countByRole(Role.DOCENTE);
        long totalStudents = userAccountRepository.countByRole(Role.ESTUDIANTE);
        long totalUsers = totalAdmins + totalTeachers + totalStudents;

        long activeUsers = userAccountRepository.countByActive(true);
        long inactiveUsers = userAccountRepository.countByActive(false);
        long activeTeachers = userAccountRepository.countByRoleAndActive(Role.DOCENTE, true);
        long activeStudents = userAccountRepository.countByRoleAndActive(Role.ESTUDIANTE, true);

        // Métricas complementarias de módulos. Si un módulo no tiene datos, el conteo es 0.
        long totalConcepts = conceptContentRepository.count();
        long publishedConcepts = conceptContentRepository.countByStatus(ConceptStatus.PUBLISHED);
        long totalEvaluations = evaluationRepository.count();
        long publishedEvaluations = evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED);
        long submittedAttempts = evaluationAttemptRepository.countByStatusIn(
                List.of(AttemptStatus.SUBMITTED, AttemptStatus.GRADED));

        return new AdminSummaryResponse(
                totalUsers, totalAdmins, totalTeachers, totalStudents,
                activeUsers, inactiveUsers, activeTeachers, activeStudents,
                totalConcepts, publishedConcepts, totalEvaluations, publishedEvaluations,
                submittedAttempts);
    }

    // =========================================================================
    // LISTADO UNIFICADO DE USUARIOS
    // =========================================================================

    public List<AdminUserResponse> listUsers() {
        Map<Long, TeacherProfile> teacherByUserId = teacherProfileRepository.findAll().stream()
                .collect(Collectors.toMap(t -> t.getUser().getId(), t -> t));
        Map<Long, StudentProfile> studentByUserId = studentProfileRepository.findAll().stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s));

        return userAccountRepository.findAll().stream()
                .map(user -> toAdminUserResponse(user, teacherByUserId, studentByUserId))
                .sorted(Comparator
                        .comparing((AdminUserResponse u) -> u.role().name())
                        .thenComparing(AdminUserResponse::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // =========================================================================
    // ACTIVIDAD RECIENTE (derivada de registros existentes)
    // =========================================================================

    public AdminActivityResponse getActivity() {
        List<AdminActivityItem> recentUsers = userAccountRepository.findTop8ByOrderByCreatedAtDesc().stream()
                .map(this::toUserActivityItem)
                .toList();

        List<AdminActivityItem> recentEvaluations = evaluationRepository.findTop5ByOrderByCreatedAtDesc().stream()
                .map(e -> new AdminActivityItem(e.getTitle(), teacherFullName(e.getCreatedByTeacher()), e.getCreatedAt()))
                .toList();

        List<AdminActivityItem> recentConcepts = conceptContentRepository.findTop5ByOrderByCreatedAtDesc().stream()
                .map(c -> new AdminActivityItem(c.getTitle(), teacherFullName(c.getCreatedByTeacher()), c.getCreatedAt()))
                .toList();

        return new AdminActivityResponse(recentUsers, recentEvaluations, recentConcepts);
    }

    // =========================================================================
    // MAPEO
    // =========================================================================

    private AdminUserResponse toAdminUserResponse(UserAccount user,
                                                  Map<Long, TeacherProfile> teacherByUserId,
                                                  Map<Long, StudentProfile> studentByUserId) {
        String fullName;
        String code = null;

        switch (user.getRole()) {
            case DOCENTE -> {
                TeacherProfile teacher = teacherByUserId.get(user.getId());
                fullName = teacher != null
                        ? teacher.getNames() + " " + teacher.getLastNames()
                        : user.getUsername();
            }
            case ESTUDIANTE -> {
                StudentProfile student = studentByUserId.get(user.getId());
                fullName = student != null
                        ? student.getNames() + " " + student.getLastNames()
                        : user.getUsername();
                code = student != null ? student.getStudentCode() : null;
            }
            default -> fullName = user.getUsername();
        }

        return new AdminUserResponse(
                user.getId(),
                fullName,
                user.getUsername(),
                code,
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword(),
                user.getCreatedAt());
    }

    private AdminActivityItem toUserActivityItem(UserAccount user) {
        String roleLabel = switch (user.getRole()) {
            case ADMINISTRADOR -> "Administrador";
            case DOCENTE -> "Docente";
            case ESTUDIANTE -> "Estudiante";
        };
        return new AdminActivityItem(user.getUsername(), roleLabel, user.getCreatedAt());
    }

    private String teacherFullName(TeacherProfile teacher) {
        if (teacher == null) {
            return null;
        }
        return teacher.getNames() + " " + teacher.getLastNames();
    }
}
