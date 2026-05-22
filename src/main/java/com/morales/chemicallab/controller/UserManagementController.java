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

    // =========================================================================
    // DOCENTES
    // =========================================================================

    @PostMapping("/teachers")
    public ResponseEntity<TeacherResponse> registrarDocente(@Valid @RequestBody CreateTeacherRequest request) {
        TeacherResponse response = userManagementService.createTeacher(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/teachers")
    public List<TeacherResponse> listarDocentes() {
        return userManagementService.listTeachers();
    }

    @PatchMapping("/teachers/{teacherUserId}/deactivate")
    public TeacherResponse desactivarDocente(@PathVariable Long teacherUserId) {
        return userManagementService.deactivateTeacher(teacherUserId);
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
    public List<StudentResponse> listarEstudiantesDeDocente(@PathVariable Long teacherUserId) {
        return userManagementService.listStudentsByTeacher(teacherUserId);
    }

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

    // =========================================================================
    // USUARIOS (general)
    // =========================================================================

    @PatchMapping("/{userId}/deactivate")
    public UserResponse desactivarUsuario(@PathVariable Long userId) {
        return userManagementService.deactivateUser(userId);
    }
}
