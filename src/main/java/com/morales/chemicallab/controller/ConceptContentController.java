package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.service.ConceptContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints de los contenidos conceptuales de química. El acceso por rol se
 * controla en SecurityConfig; el docente y el estudiante se identifican a partir
 * del usuario autenticado (no de identificadores enviados por el cliente).
 */
@RestController
@RequestMapping("/api/concepts")
@RequiredArgsConstructor
public class ConceptContentController {

    private final ConceptContentService conceptContentService;

    // =========================================================================
    // DOCENTE
    // =========================================================================

    @PostMapping("/teacher")
    public ResponseEntity<ConceptContentResponse> crearContenido(
            Authentication authentication,
            @Valid @RequestBody CreateConceptContentRequest request) {
        ConceptContentResponse response = conceptContentService.createConceptContent(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/teacher")
    public List<ConceptContentResponse> listarMisContenidos(Authentication authentication) {
        return conceptContentService.listConceptsByTeacher(authentication.getName());
    }

    @GetMapping("/teacher/{conceptId}")
    public ConceptContentResponse verMiContenido(
            Authentication authentication,
            @PathVariable Long conceptId) {
        return conceptContentService.getConceptByIdForTeacher(authentication.getName(), conceptId);
    }

    @PutMapping("/teacher/{conceptId}")
    public ConceptContentResponse editarContenido(
            Authentication authentication,
            @PathVariable Long conceptId,
            @Valid @RequestBody UpdateConceptContentRequest request) {
        return conceptContentService.updateConceptContent(authentication.getName(), conceptId, request);
    }

    @PatchMapping("/teacher/{conceptId}/publish")
    public ConceptContentResponse publicarContenido(
            Authentication authentication,
            @PathVariable Long conceptId) {
        return conceptContentService.publishConceptContent(authentication.getName(), conceptId);
    }

    @PatchMapping("/teacher/{conceptId}/archive")
    public ConceptContentResponse archivarContenido(
            Authentication authentication,
            @PathVariable Long conceptId) {
        return conceptContentService.archiveConceptContent(authentication.getName(), conceptId);
    }

    @PostMapping("/teacher/{conceptId}/assignments")
    public ResponseEntity<ConceptAssignmentResponse> asignarContenido(
            Authentication authentication,
            @PathVariable Long conceptId,
            @Valid @RequestBody AssignConceptContentRequest request) {
        ConceptAssignmentResponse response =
                conceptContentService.assignConceptToSection(authentication.getName(), conceptId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/teacher/{conceptId}/assignments/{assignmentId}/deactivate")
    public ConceptAssignmentResponse desactivarAsignacion(
            Authentication authentication,
            @PathVariable Long conceptId,
            @PathVariable Long assignmentId) {
        return conceptContentService.deactivateAssignment(authentication.getName(), conceptId, assignmentId);
    }

    // =========================================================================
    // ESTUDIANTE
    // =========================================================================

    @GetMapping("/student")
    public List<StudentConceptContentResponse> listarContenidosEstudiante(Authentication authentication) {
        return conceptContentService.listPublishedConceptsForStudent(authentication.getName());
    }

    @GetMapping("/student/{conceptId}")
    public StudentConceptContentResponse verContenidoEstudiante(
            Authentication authentication,
            @PathVariable Long conceptId) {
        return conceptContentService.getPublishedConceptForStudent(authentication.getName(), conceptId);
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @GetMapping("/admin")
    public List<ConceptContentResponse> listarTodos() {
        return conceptContentService.listAllConcepts();
    }

    @GetMapping("/admin/{conceptId}")
    public ConceptContentResponse verCualquierContenido(@PathVariable Long conceptId) {
        return conceptContentService.getAnyConceptById(conceptId);
    }
}
