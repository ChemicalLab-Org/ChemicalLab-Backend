package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AdminActivityItem;
import com.morales.chemicallab.dto.AdminActivityResponse;
import com.morales.chemicallab.dto.AdminPasswordResetResponse;
import com.morales.chemicallab.dto.AdminSummaryResponse;
import com.morales.chemicallab.dto.AdminUserCreatedResponse;
import com.morales.chemicallab.dto.AdminUserResponse;
import com.morales.chemicallab.dto.CreateUserRequest;
import com.morales.chemicallab.dto.TeacherOptionResponse;
import com.morales.chemicallab.dto.UpdateUserRequest;
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
    private final AuditLogService auditLogService;

    // Alfabeto sin caracteres ambiguos (0/O, 1/l/I) para contraseñas temporales legibles.
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 10;
    private final SecureRandom secureRandom = new SecureRandom();

    // Tipo de recurso afectado en los eventos de trazabilidad de gestión de usuarios.
    private static final String TARGET_USER = "UserAccount";

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

        // Importante: nunca se registra la contraseña temporal generada.
        auditLogService.recordWarning(LogEventType.PASSWORD_RESET, "UserAccount", user.getId(),
                user.getUsername(), "Restablecer contraseña",
                "Se restableció la contraseña del usuario " + user.getUsername() + ".",
                "role=" + user.getRole());

        return new AdminPasswordResetResponse(
                "Contraseña restablecida correctamente.", temporaryPassword);
    }

    // =========================================================================
    // GESTIÓN DE USUARIOS (crear / editar / activar / desactivar)
    // =========================================================================

    /**
     * Crea un usuario desde el panel administrativo según el rol indicado. El backend
     * genera una contraseña temporal, la cifra con BCrypt, marca {@code temporaryPassword}
     * y devuelve el texto plano una sola vez. Nunca se registra la contraseña en logs.
     *
     * <ul>
     *   <li><strong>ADMINISTRADOR:</strong> requiere {@code username}; el correo es opcional.</li>
     *   <li><strong>DOCENTE:</strong> requiere nombres, apellidos y {@code username}.</li>
     *   <li><strong>ESTUDIANTE:</strong> requiere nombres, apellidos, grado, sección y un
     *       docente responsable activo; el código es opcional (se genera si falta).</li>
     * </ul>
     */
    @Transactional
    public AdminUserCreatedResponse createUser(CreateUserRequest request) {
        Role role = request.role();
        String temporaryPassword = generateTemporaryPassword();
        String encoded = passwordEncoder.encode(temporaryPassword);

        UserAccount createdUser = switch (role) {
            case ADMINISTRADOR -> createAdministrator(request, encoded);
            case DOCENTE -> createTeacher(request, encoded);
            case ESTUDIANTE -> createStudent(request, encoded);
        };

        return new AdminUserCreatedResponse(toAdminUserResponse(createdUser), temporaryPassword);
    }

    private UserAccount createAdministrator(CreateUserRequest request, String encodedPassword) {
        String username = requireText(request.username(), "El nombre de usuario es obligatorio.");
        String email = normalizeEmail(request.email());
        validateUsernameAvailable(username);
        validateEmailAvailable(email, null);

        UserAccount user = UserAccount.builder()
                .username(username)
                .email(email)
                .password(encodedPassword)
                .role(Role.ADMINISTRADOR)
                .active(true)
                .temporaryPassword(true)
                .build();
        userAccountRepository.save(user);

        auditLogService.recordInfo(LogEventType.USER_CREATED, TARGET_USER, user.getId(),
                user.getUsername(), "Registrar administrador",
                "Se registró un nuevo administrador (" + user.getUsername() + ").",
                "role=ADMINISTRADOR");
        return user;
    }

    private UserAccount createTeacher(CreateUserRequest request, String encodedPassword) {
        String names = requireText(request.names(), "El nombre es obligatorio.");
        String lastNames = requireText(request.lastNames(), "Los apellidos son obligatorios.");
        String username = requireText(request.username(), "El nombre de usuario es obligatorio.");
        String email = normalizeEmail(request.email());
        validateUsernameAvailable(username);
        validateEmailAvailable(email, null);

        UserAccount user = UserAccount.builder()
                .username(username)
                .email(email)
                .password(encodedPassword)
                .role(Role.DOCENTE)
                .active(true)
                .temporaryPassword(true)
                .build();
        userAccountRepository.save(user);

        TeacherProfile teacher = TeacherProfile.builder()
                .user(user)
                .names(names)
                .lastNames(lastNames)
                .build();
        teacherProfileRepository.save(teacher);

        auditLogService.recordInfo(LogEventType.USER_CREATED, TARGET_USER, user.getId(),
                names + " " + lastNames, "Registrar docente",
                "Se registró un nuevo docente (" + user.getUsername() + ").", "role=DOCENTE");
        return user;
    }

    private UserAccount createStudent(CreateUserRequest request, String encodedPassword) {
        String names = requireText(request.names(), "El nombre es obligatorio.");
        String lastNames = requireText(request.lastNames(), "Los apellidos son obligatorios.");
        String grade = StudentValidation.normalizedGrade(request.grade());
        String section = StudentValidation.normalizedSection(request.section());
        TeacherProfile teacher = resolveActiveTeacher(request.teacherUserId());

        String studentCode;
        if (request.studentCode() == null || request.studentCode().isBlank()) {
            studentCode = generateStudentCode();
        } else {
            studentCode = request.studentCode().trim().toUpperCase();
            if (studentProfileRepository.existsByStudentCode(studentCode)) {
                throw new IllegalArgumentException("El código de estudiante ya está registrado.");
            }
        }
        validateUsernameAvailable(studentCode);

        UserAccount user = UserAccount.builder()
                .username(studentCode)
                .password(encodedPassword)
                .role(Role.ESTUDIANTE)
                .active(true)
                .temporaryPassword(true)
                .build();
        userAccountRepository.save(user);

        StudentProfile student = StudentProfile.builder()
                .user(user)
                .teacher(teacher)
                .studentCode(studentCode)
                .names(names)
                .lastNames(lastNames)
                .grade(grade)
                .section(section)
                .build();
        studentProfileRepository.save(student);

        auditLogService.recordInfo(LogEventType.USER_CREATED, TARGET_USER, user.getId(),
                names + " " + lastNames, "Registrar estudiante",
                "Se registró un nuevo estudiante (" + studentCode + ").", "role=ESTUDIANTE");
        return user;
    }

    /**
     * Actualiza los datos básicos de un usuario según su rol. No cambia el nombre de
     * usuario ni el código (para no romper relaciones), el rol, la contraseña ni el estado
     * activo/inactivo (este último se gestiona con los endpoints específicos).
     */
    @Transactional
    public AdminUserResponse updateUser(Long userId, UpdateUserRequest request) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        switch (user.getRole()) {
            case ADMINISTRADOR -> user.setEmail(applyEmail(request.email(), user));
            case DOCENTE -> {
                String names = requireText(request.names(), "El nombre es obligatorio.");
                String lastNames = requireText(request.lastNames(), "Los apellidos son obligatorios.");
                TeacherProfile teacher = teacherProfileRepository.findByUser(user)
                        .orElseThrow(() -> new IllegalArgumentException("Perfil de docente no encontrado."));
                teacher.setNames(names);
                teacher.setLastNames(lastNames);
                user.setEmail(applyEmail(request.email(), user));
                teacherProfileRepository.save(teacher);
            }
            case ESTUDIANTE -> {
                String names = requireText(request.names(), "El nombre es obligatorio.");
                String lastNames = requireText(request.lastNames(), "Los apellidos son obligatorios.");
                String grade = StudentValidation.normalizedGrade(request.grade());
                String section = StudentValidation.normalizedSection(request.section());
                StudentProfile student = studentProfileRepository.findByUser_Id(user.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Perfil de estudiante no encontrado."));
                student.setNames(names);
                student.setLastNames(lastNames);
                student.setGrade(grade);
                student.setSection(section);
                if (request.teacherUserId() != null) {
                    student.setTeacher(resolveActiveTeacher(request.teacherUserId()));
                }
                user.setEmail(applyEmail(request.email(), user));
                studentProfileRepository.save(student);
            }
        }

        userAccountRepository.save(user);

        auditLogService.recordInfo(LogEventType.USER_UPDATED, TARGET_USER, user.getId(),
                user.getUsername(), "Actualizar usuario",
                "Se actualizaron los datos del usuario " + user.getUsername() + ".",
                "role=" + user.getRole());

        return toAdminUserResponse(user);
    }

    /** Reactiva un usuario previamente desactivado. */
    @Transactional
    public AdminUserResponse activateUser(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        user.setActive(true);
        userAccountRepository.save(user);

        auditLogService.recordInfo(LogEventType.USER_REACTIVATED, TARGET_USER, user.getId(),
                user.getUsername(), "Reactivar usuario",
                "Se reactivó el usuario " + user.getUsername() + ".", "role=" + user.getRole());

        return toAdminUserResponse(user);
    }

    /**
     * Desactiva un usuario (no lo elimina). Protege contra dos escenarios peligrosos: que
     * el administrador se desactive a sí mismo y que se desactive al último administrador
     * activo, lo que dejaría al sistema sin acceso administrativo.
     */
    @Transactional
    public AdminUserResponse deactivateUser(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        Long selfId = authenticatedUserId();
        if (selfId != null && selfId.equals(user.getId())) {
            throw new IllegalArgumentException("No puede desactivar su propia cuenta de administrador.");
        }

        if (user.getRole() == Role.ADMINISTRADOR && Boolean.TRUE.equals(user.getActive())
                && userAccountRepository.countByRoleAndActive(Role.ADMINISTRADOR, true) <= 1) {
            throw new IllegalArgumentException("No puede desactivar al último administrador activo.");
        }

        user.setActive(false);
        userAccountRepository.save(user);

        auditLogService.recordWarning(LogEventType.USER_DEACTIVATED, TARGET_USER, user.getId(),
                user.getUsername(), "Desactivar usuario",
                "Se desactivó el usuario " + user.getUsername() + ".", "role=" + user.getRole());

        return toAdminUserResponse(user);
    }

    /** Lista los docentes activos como opciones para asignar el docente responsable. */
    public List<TeacherOptionResponse> listActiveTeacherOptions() {
        return teacherProfileRepository.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getUser().getActive()))
                .map(t -> new TeacherOptionResponse(
                        t.getUser().getId(),
                        t.getNames() + " " + t.getLastNames(),
                        t.getUser().getUsername()))
                .sorted(Comparator.comparing(TeacherOptionResponse::fullName, String.CASE_INSENSITIVE_ORDER))
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
                                                  Map<Long, StudentProfile> studentByUserId,
                                                  Long selfId) {
        TeacherProfile teacher = user.getRole() == Role.DOCENTE ? teacherByUserId.get(user.getId()) : null;
        StudentProfile student = user.getRole() == Role.ESTUDIANTE ? studentByUserId.get(user.getId()) : null;
        boolean protectedAccount = selfId != null && selfId.equals(user.getId());
        return buildUserResponse(user, teacher, student, protectedAccount);
    }

    /**
     * Mapea un único usuario a su vista administrativa, resolviendo el nombre completo y el
     * código a partir del perfil correspondiente al rol. Se usa tras crear o actualizar.
     */
    private AdminUserResponse toAdminUserResponse(UserAccount user) {
        TeacherProfile teacher = user.getRole() == Role.DOCENTE
                ? teacherProfileRepository.findByUser(user).orElse(null)
                : null;
        StudentProfile student = user.getRole() == Role.ESTUDIANTE
                ? studentProfileRepository.findByUser_Id(user.getId()).orElse(null)
                : null;
        Long selfId = authenticatedUserId();
        boolean protectedAccount = selfId != null && selfId.equals(user.getId());
        return buildUserResponse(user, teacher, student, protectedAccount);
    }

    /**
     * Construye la vista administrativa a partir de la cuenta y, según el rol, su perfil
     * de docente o estudiante. Los campos no aplicables al rol quedan en {@code null}.
     */
    private AdminUserResponse buildUserResponse(UserAccount user, TeacherProfile teacher,
                                                StudentProfile student, boolean protectedAccount) {
        String names = null;
        String lastNames = null;
        String code = null;
        String grade = null;
        String section = null;
        Long teacherUserId = null;

        if (teacher != null) {
            names = teacher.getNames();
            lastNames = teacher.getLastNames();
        } else if (student != null) {
            names = student.getNames();
            lastNames = student.getLastNames();
            code = student.getStudentCode();
            grade = student.getGrade();
            section = student.getSection();
            teacherUserId = student.getTeacher() != null ? student.getTeacher().getUser().getId() : null;
        }

        String fullName = (names != null) ? names + " " + lastNames : user.getUsername();

        return new AdminUserResponse(
                user.getId(), fullName, names, lastNames, user.getUsername(), code,
                user.getEmail(), user.getRole(), user.getActive(), user.getTemporaryPassword(),
                user.getCreatedAt(), teacherUserId, grade, section, protectedAccount);
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

    /** Exige que un campo de texto obligatorio tenga contenido; devuelve el valor recortado. */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** Normaliza el correo: {@code null} si viene vacío, recortado en caso contrario. */
    private String normalizeEmail(String email) {
        return (email != null && !email.isBlank()) ? email.trim() : null;
    }

    /**
     * Resuelve el correo a guardar al actualizar: si no se envía valor, conserva el actual;
     * si cambia, valida que no esté en uso por otra cuenta.
     */
    private String applyEmail(String email, UserAccount user) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return user.getEmail();
        }
        validateEmailAvailable(normalized, user.getId());
        return normalized;
    }

    private void validateUsernameAvailable(String username) {
        if (userAccountRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("El nombre de usuario ya está registrado.");
        }
    }

    /**
     * Verifica que el correo no esté en uso por otra cuenta. {@code currentUserId} excluye al
     * propio usuario en operaciones de actualización (para no chocar con su correo actual).
     */
    private void validateEmailAvailable(String email, Long currentUserId) {
        if (email == null) {
            return;
        }
        userAccountRepository.findByEmail(email).ifPresent(existing -> {
            if (currentUserId == null || !existing.getId().equals(currentUserId)) {
                throw new IllegalArgumentException("El correo ya está registrado.");
            }
        });
    }

    /** Resuelve el docente responsable por id de cuenta, exigiendo que exista y esté activo. */
    private TeacherProfile resolveActiveTeacher(Long teacherUserId) {
        if (teacherUserId == null) {
            throw new IllegalArgumentException("Debe seleccionar un docente responsable.");
        }
        UserAccount teacherUser = userAccountRepository.findById(teacherUserId)
                .orElseThrow(() -> new IllegalArgumentException("El docente responsable no existe."));
        if (teacherUser.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario seleccionado no es un docente.");
        }
        if (!Boolean.TRUE.equals(teacherUser.getActive())) {
            throw new IllegalArgumentException("El docente responsable no está activo.");
        }
        return teacherProfileRepository.findByUser(teacherUser)
                .orElseThrow(() -> new IllegalArgumentException("Perfil de docente no encontrado."));
    }

    /** Genera el siguiente código de estudiante con el patrón {@code ESTXXXX}. */
    private String generateStudentCode() {
        int max = 0;
        for (StudentProfile s : studentProfileRepository.findAll()) {
            String code = s.getStudentCode();
            if (code != null && code.matches("EST\\d{4}")) {
                max = Math.max(max, Integer.parseInt(code.substring(3)));
            }
        }
        return String.format("EST%04d", max + 1);
    }

    private String generateTemporaryPassword() {
        StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            builder.append(TEMP_PASSWORD_ALPHABET.charAt(secureRandom.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }
}
