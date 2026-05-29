package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class UserManagementService {

    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final PasswordEncoder passwordEncoder;

    // =========================================================================
    // DOCENTES
    // =========================================================================

    public TeacherResponse createTeacher(CreateTeacherRequest request) {
        if (userAccountRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("El nombre de usuario ya está registrado.");
        }
        if (request.email() != null && !request.email().isBlank()) {
            if (userAccountRepository.existsByEmail(request.email())) {
                throw new IllegalArgumentException("El correo ya está registrado.");
            }
        }

        UserAccount user = UserAccount.builder()
                .username(request.username())
                .email(request.email() != null && !request.email().isBlank() ? request.email() : null)
                .password(passwordEncoder.encode(request.temporaryPassword()))
                .role(Role.DOCENTE)
                .active(true)
                .temporaryPassword(true)
                .build();

        userAccountRepository.save(user);

        TeacherProfile teacher = TeacherProfile.builder()
                .user(user)
                .names(request.names())
                .lastNames(request.lastNames())
                .build();

        teacherProfileRepository.save(teacher);

        return toTeacherResponse(teacher);
    }

    @Transactional(readOnly = true)
    public List<TeacherResponse> listTeachers() {
        return teacherProfileRepository.findAll()
                .stream()
                .map(this::toTeacherResponse)
                .toList();
    }

    public TeacherResponse deactivateTeacher(Long teacherUserId) {
        UserAccount user = userAccountRepository.findById(teacherUserId)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado."));

        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario indicado no pertenece al rol docente.");
        }

        user.setActive(false);
        userAccountRepository.save(user);

        TeacherProfile teacher = teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado."));

        return toTeacherResponse(teacher);
    }

    /**
     * Restablece la contraseña temporal de un docente. La realiza el administrador:
     * la nueva contraseña se cifra con BCrypt y el docente queda obligado a cambiarla
     * en su próximo ingreso (temporaryPassword = true).
     */
    public PasswordChangeResponse resetTeacherPassword(Long teacherUserId, ResetPasswordRequest request) {
        validatePasswordsMatch(request);

        UserAccount user = userAccountRepository.findById(teacherUserId)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado."));

        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario indicado no pertenece al rol docente.");
        }

        user.setPassword(passwordEncoder.encode(request.newTemporaryPassword()));
        user.setTemporaryPassword(true);
        userAccountRepository.save(user);

        return new PasswordChangeResponse("Contraseña restablecida correctamente", true);
    }

    // =========================================================================
    // ESTUDIANTES
    // =========================================================================

    public StudentResponse createStudent(Long teacherUserId, CreateStudentRequest request) {
        TeacherProfile teacher = findTeacherProfileByUserId(teacherUserId);

        String studentCode;
        if (request.studentCode() == null || request.studentCode().isBlank()) {
            studentCode = generateStudentCode();
        } else {
            studentCode = normalizeStudentCode(request.studentCode());
            if (studentProfileRepository.existsByStudentCode(studentCode)) {
                throw new IllegalArgumentException("El código de estudiante ya está registrado.");
            }
        }

        if (userAccountRepository.existsByUsername(studentCode)) {
            throw new IllegalArgumentException("El nombre de usuario ya está registrado.");
        }

        UserAccount user = UserAccount.builder()
                .username(studentCode)
                .password(passwordEncoder.encode(request.temporaryPassword()))
                .role(Role.ESTUDIANTE)
                .active(true)
                .temporaryPassword(true)
                .build();

        userAccountRepository.save(user);

        StudentProfile student = StudentProfile.builder()
                .user(user)
                .teacher(teacher)
                .studentCode(studentCode)
                .names(request.names())
                .lastNames(request.lastNames())
                .grade(request.grade())
                .section(request.section())
                .build();

        studentProfileRepository.save(student);

        return toStudentResponse(student);
    }

    @Transactional(readOnly = true)
    public List<StudentResponse> listStudentsByTeacher(Long teacherUserId) {
        TeacherProfile teacher = findTeacherProfileByUserId(teacherUserId);
        return studentProfileRepository.findByTeacher(teacher)
                .stream()
                .map(this::toStudentResponse)
                .toList();
    }

    public StudentResponse updateStudent(Long teacherUserId, Long studentId, UpdateStudentRequest request) {
        TeacherProfile teacher = findTeacherProfileByUserId(teacherUserId);

        StudentProfile student = studentProfileRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado."));

        validateStudentBelongsToTeacher(student, teacher);

        student.setNames(request.names());
        student.setLastNames(request.lastNames());
        student.setGrade(request.grade());
        student.setSection(request.section());
        student.getUser().setActive(request.active());

        if (request.studentCode() != null && !request.studentCode().isBlank()) {
            String newCode = normalizeStudentCode(request.studentCode());
            if (!newCode.equals(student.getStudentCode())) {
                if (studentProfileRepository.existsByStudentCode(newCode)) {
                    throw new IllegalArgumentException("El código de estudiante ya está registrado.");
                }
                student.setStudentCode(newCode);
                student.getUser().setUsername(newCode);
            }
        }

        userAccountRepository.save(student.getUser());
        studentProfileRepository.save(student);

        return toStudentResponse(student);
    }

    public StudentResponse deactivateStudent(Long teacherUserId, Long studentId) {
        TeacherProfile teacher = findTeacherProfileByUserId(teacherUserId);

        StudentProfile student = studentProfileRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado."));

        validateStudentBelongsToTeacher(student, teacher);

        student.getUser().setActive(false);
        userAccountRepository.save(student.getUser());

        return toStudentResponse(student);
    }

    /**
     * Restablece la contraseña temporal de un estudiante. La realiza el docente
     * dueño del estudiante: la nueva contraseña se cifra con BCrypt y el estudiante
     * queda obligado a cambiarla en su próximo ingreso (temporaryPassword = true).
     */
    public PasswordChangeResponse resetStudentPassword(Long teacherUserId, Long studentId, ResetPasswordRequest request) {
        validatePasswordsMatch(request);
        validateAuthenticatedTeacher(teacherUserId);

        TeacherProfile teacher = findTeacherProfileByUserId(teacherUserId);

        StudentProfile student = studentProfileRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado."));

        validateStudentBelongsToTeacher(student, teacher);

        UserAccount account = student.getUser();
        account.setPassword(passwordEncoder.encode(request.newTemporaryPassword()));
        account.setTemporaryPassword(true);
        userAccountRepository.save(account);

        return new PasswordChangeResponse("Contraseña restablecida correctamente", true);
    }

    // =========================================================================
    // USUARIOS (general)
    // =========================================================================

    public UserResponse deactivateUser(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        user.setActive(false);
        userAccountRepository.save(user);

        return toUserResponse(user);
    }

    // =========================================================================
    // MÉTODOS PRIVADOS DE MAPEO
    // =========================================================================

    private UserResponse toUserResponse(UserAccount user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private TeacherResponse toTeacherResponse(TeacherProfile teacher) {
        return new TeacherResponse(
                teacher.getId(),
                teacher.getUser().getId(),
                teacher.getUser().getUsername(),
                teacher.getUser().getEmail(),
                teacher.getNames(),
                teacher.getLastNames(),
                teacher.getUser().getActive(),
                teacher.getUser().getTemporaryPassword()
        );
    }

    private StudentResponse toStudentResponse(StudentProfile student) {
        return new StudentResponse(
                student.getId(),
                student.getUser().getId(),
                student.getStudentCode(),
                student.getUser().getUsername(),
                student.getNames(),
                student.getLastNames(),
                student.getGrade(),
                student.getSection(),
                student.getUser().getActive(),
                student.getUser().getTemporaryPassword(),
                student.getTeacher().getId()
        );
    }

    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    private TeacherProfile findTeacherProfileByUserId(Long teacherUserId) {
        UserAccount user = userAccountRepository.findById(teacherUserId)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado."));

        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario indicado no pertenece al rol docente.");
        }

        return teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado."));
    }

    private void validateStudentBelongsToTeacher(StudentProfile student, TeacherProfile teacher) {
        if (!student.getTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("El estudiante no pertenece al docente indicado.");
        }
    }

    private void validatePasswordsMatch(ResetPasswordRequest request) {
        if (!request.newTemporaryPassword().equals(request.confirmTemporaryPassword())) {
            throw new IllegalArgumentException("La nueva contraseña y su confirmación no coinciden.");
        }
    }

    /**
     * Verifica que el docente autenticado sea el mismo indicado en la ruta, de modo
     * que un docente no pueda restablecer contraseñas de estudiantes de otro docente.
     */
    private void validateAuthenticatedTeacher(Long teacherUserId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalArgumentException("No fue posible identificar al docente autenticado.");
        }

        UserAccount authenticatedUser = userAccountRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("No fue posible identificar al docente autenticado."));

        if (!authenticatedUser.getId().equals(teacherUserId)) {
            throw new IllegalArgumentException("No puede restablecer contraseñas de estudiantes de otro docente.");
        }
    }

    private String generateStudentCode() {
        List<StudentProfile> all = studentProfileRepository.findAll();
        int max = 0;
        for (StudentProfile s : all) {
            String code = s.getStudentCode();
            if (code != null && code.matches("EST\\d{4}")) {
                int num = Integer.parseInt(code.substring(3));
                if (num > max) max = num;
            }
        }
        return String.format("EST%04d", max + 1);
    }

    private String normalizeStudentCode(String studentCode) {
        return studentCode.trim().toUpperCase();
    }
}
