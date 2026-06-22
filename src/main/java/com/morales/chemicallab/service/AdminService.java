package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AdminActivityItem;
import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
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
    private final PasswordEncoder passwordEncoder;

    // Alfabeto sin caracteres ambiguos (0/O, 1/l/I) para contraseñas temporales legibles.
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 10;
    private final SecureRandom secureRandom = new SecureRandom();

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

        Long selfId = authenticatedUserId();

        return userAccountRepository.findAll().stream()
                .map(user -> toAdminUserResponse(user, teacherByUserId, studentByUserId, selfId))
                .sorted(Comparator
                        .comparing((AdminUserResponse u) -> u.role().name())
                        .thenComparing(AdminUserResponse::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Restablece la contraseña de cualquier usuario administrable (docente, estudiante u
     * otro), tomando como base directamente {@link UserAccount}. No depende del perfil ni
     * de quién creó al usuario, ni de que tenga correo. Genera una contraseña temporal,
     * la cifra con BCrypt, marca {@code temporaryPassword = true} y devuelve el texto plano
     * una sola vez para que el administrador lo entregue al usuario.
     *
     * <p>No permite restablecer la contraseña de la propia cuenta autenticada: para eso
     * existe el cambio de contraseña personal.</p>
     */
    @Transactional
    public AdminPasswordResetResponse resetUserPassword(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        Long selfId = authenticatedUserId();
        if (selfId != null && selfId.equals(user.getId())) {
            throw new IllegalArgumentException(
                    "Usa el cambio de contraseña personal para tu propia cuenta.");
        }

        String temporaryPassword = generateTemporaryPassword();
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setTemporaryPassword(true);
        userAccountRepository.save(user);

        return new AdminPasswordResetResponse(
                "Contraseña restablecida correctamente.", temporaryPassword);
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
                                                  Map<Long, StudentProfile> studentByUserId,
                                                  Long selfId) {
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

        boolean protectedAccount = selfId != null && selfId.equals(user.getId());

        return new AdminUserResponse(
                user.getId(),
                fullName,
                user.getUsername(),
                code,
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword(),
                user.getCreatedAt(),
                protectedAccount);
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

    /**
     * Id del usuario autenticado a partir del contexto de seguridad, o {@code null} si no
     * es posible identificarlo (por ejemplo, en pruebas sin autenticación configurada).
     */
    private Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return userAccountRepository.findByUsername(authentication.getName())
                .map(UserAccount::getId)
                .orElse(null);
    }

    private String generateTemporaryPassword() {
        StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            builder.append(TEMP_PASSWORD_ALPHABET.charAt(secureRandom.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }
}
