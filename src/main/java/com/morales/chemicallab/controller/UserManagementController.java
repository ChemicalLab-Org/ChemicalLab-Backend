package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;
    private final com.morales.chemicallab.security.AuthenticationLimits limits;

    // =========================================================================
    // DOCENTES
    // =========================================================================

    @PostMapping("/teachers")
    public ResponseEntity<TeacherResponse> registrarDocente(@Valid @RequestBody CreateTeacherRequest request) {
        TeacherResponse response = userManagementService.createTeacher(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/teachers")
    public QueryPage<TeacherResponse> listarDocentes(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(required=false) String search,@RequestParam(defaultValue="all") String status) {
        return userManagementService.listTeachers(page,size,search,status);
    }

    @PatchMapping("/teachers/{teacherUserId}/deactivate")
    public TeacherResponse desactivarDocente(@PathVariable Long teacherUserId) {
        return userManagementService.deactivateTeacher(teacherUserId);
    }

    @PatchMapping("/teachers/{teacherUserId}/reset-password")
    public PasswordChangeResponse restablecerContrasenaDocente(
            @PathVariable Long teacherUserId,
            @Valid @RequestBody ResetPasswordRequest request) {
        limits.resetUser(teacherUserId);
        return userManagementService.resetTeacherPassword(teacherUserId, request);
    }

    // =========================================================================
    // ESTUDIANTES
    // =========================================================================

    @PostMapping("/teachers/{teacherUserId}/students")
    public ResponseEntity<StudentResponse> registrarEstudiante(
            @PathVariable Long teacherUserId,
            @Valid @RequestBody CreateStudentRequest request) {
        StudentResponse response = userManagementService.createStudent(teacherUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/teachers/{teacherUserId}/students")
    public QueryPage<StudentResponse> listarEstudiantesDeDocente(@PathVariable Long teacherUserId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(required=false) String search,@RequestParam(required=false) String grade,@RequestParam(required=false) String section,@RequestParam(defaultValue="all") String status) {
        return userManagementService.listStudentsByTeacher(teacherUserId,page,size,search,grade,section,status);
    }

    @GetMapping("/teachers/{teacherUserId}/students/summary")
    public RosterSummary resumenEstudiantes(@PathVariable Long teacherUserId){return userManagementService.studentSummary(teacherUserId);}

    @PutMapping("/teachers/{teacherUserId}/students/{studentId}")
    public StudentResponse editarEstudiante(
            @PathVariable Long teacherUserId,
            @PathVariable Long studentId,
            @Valid @RequestBody UpdateStudentRequest request) {
        return userManagementService.updateStudent(teacherUserId, studentId, request);
    }

    @PatchMapping("/teachers/{teacherUserId}/students/{studentId}/deactivate")
    public StudentResponse desactivarEstudiante(
            @PathVariable Long teacherUserId,
            @PathVariable Long studentId) {
        return userManagementService.deactivateStudent(teacherUserId, studentId);
    }

    @PatchMapping("/teachers/{teacherUserId}/students/{studentId}/reset-password")
    public PasswordChangeResponse restablecerContrasenaEstudiante(
            @PathVariable Long teacherUserId,
            @PathVariable Long studentId,
            @Valid @RequestBody ResetPasswordRequest request) {
        limits.resetStudent(studentId);
        return userManagementService.resetStudentPassword(teacherUserId, studentId, request);
    }

    // =========================================================================
    // USUARIOS (general)
    // =========================================================================

    @PatchMapping("/{userId}/deactivate")
    public UserResponse desactivarUsuario(@PathVariable Long userId) {
        return userManagementService.deactivateUser(userId);
    }

    @PatchMapping("/{userId}/activate")
    public UserResponse activarUsuario(@PathVariable Long userId) {
        return userManagementService.activateUser(userId);
    }
}
