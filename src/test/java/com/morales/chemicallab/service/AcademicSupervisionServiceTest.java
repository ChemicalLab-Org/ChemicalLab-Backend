package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.SupervisionAssignmentResponse;
import com.morales.chemicallab.dto.SupervisionConceptResponse;
import com.morales.chemicallab.dto.SupervisionEvaluationResponse;
import com.morales.chemicallab.dto.SupervisionSummaryResponse;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de supervisión académica del administrador. Se
 * mockean los repositorios para validar el resumen, los listados de contenidos y
 * evaluaciones y la vista unificada de asignaciones, sin tocar base de datos.
 *
 * <p>Una garantía clave verificada aquí es que la vista de evaluaciones del
 * administrador es solo de metadatos: el tipo de respuesta no contiene preguntas,
 * alternativas ni la clave de respuestas.</p>
 */
@ExtendWith(MockitoExtension.class)
class AcademicSupervisionServiceTest {

    @Mock
    private ConceptContentRepository conceptContentRepository;
    @Mock
    private ConceptAssignmentRepository conceptAssignmentRepository;
    @Mock
    private EvaluationRepository evaluationRepository;
    @Mock
    private EvaluationAssignmentRepository evaluationAssignmentRepository;
    @Mock
    private EvaluationQuestionRepository evaluationQuestionRepository;
    @Mock
    private EvaluationAttemptRepository evaluationAttemptRepository;

    @InjectMocks
    private AcademicSupervisionService service;

    // =========================================================================
    // Datos de apoyo
    // =========================================================================

    private TeacherProfile teacher(Long id, String names, String lastNames) {
        return TeacherProfile.builder().id(id).names(names).lastNames(lastNames).build();
    }

    private ConceptContent concept(Long id, String title, TeacherProfile owner) {
        return ConceptContent.builder()
                .id(id).title(title).category(ConceptCategory.ACIDOS)
                .explanation("...").status(ConceptStatus.PUBLISHED)
                .createdByTeacher(owner).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private Evaluation evaluation(Long id, String title, TeacherProfile owner) {
        return Evaluation.builder()
                .id(id).title(title).topic("Óxidos").status(EvaluationStatus.PUBLISHED)
                .createdByTeacher(owner).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    // =========================================================================
    // RESUMEN
    // =========================================================================

    @Test
    void getSummary_agregaConteosYDocentesYSecciones() {
        TeacherProfile docente = teacher(1L, "Laura", "Quispe");
        ConceptContent concept = concept(1L, "Ácidos", docente);
        Evaluation evaluation = evaluation(1L, "Óxidos", docente);

        when(conceptContentRepository.count()).thenReturn(5L);
        when(conceptContentRepository.countByStatus(ConceptStatus.PUBLISHED)).thenReturn(4L);
        when(evaluationRepository.count()).thenReturn(7L);
        when(evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED)).thenReturn(6L);
        when(evaluationAttemptRepository.countByStatusIn(any())).thenReturn(15L);

        when(conceptContentRepository.findAll()).thenReturn(List.of(concept));
        when(evaluationRepository.findAll()).thenReturn(List.of(evaluation));

        ConceptAssignment ca = ConceptAssignment.builder()
                .id(1L).conceptContent(concept).teacher(docente).grade("3").section("A").active(true)
                .assignedAt(LocalDateTime.now()).build();
        EvaluationAssignment ea = EvaluationAssignment.builder()
                .id(1L).evaluation(evaluation).teacher(docente).grade("3").section("A").active(true)
                .assignedAt(LocalDateTime.now()).build();
        when(conceptAssignmentRepository.findAll()).thenReturn(List.of(ca));
        when(evaluationAssignmentRepository.findAll()).thenReturn(List.of(ea));

        SupervisionSummaryResponse summary = service.getSummary();

        assertThat(summary.totalConcepts()).isEqualTo(5L);
        assertThat(summary.publishedConcepts()).isEqualTo(4L);
        assertThat(summary.totalEvaluations()).isEqualTo(7L);
        assertThat(summary.publishedEvaluations()).isEqualTo(6L);
        assertThat(summary.submittedAttempts()).isEqualTo(15L);
        // Un solo docente con actividad (mismo perfil en contenido y evaluación).
        assertThat(summary.teachersWithActivity()).isEqualTo(1L);
        // Ambas asignaciones apuntan a "3 A": una sola sección distinta.
        assertThat(summary.sectionsWithAssignments()).isEqualTo(1L);
    }

    // =========================================================================
    // CONTENIDOS
    // =========================================================================

    @Test
    void listConcepts_incluyeDocenteYSeccionesAsignadas() {
        TeacherProfile docente = teacher(1L, "Pedro", "Martínez");
        ConceptContent concept = concept(1L, "Nomenclatura de ácidos", docente);
        when(conceptContentRepository.findAll()).thenReturn(List.of(concept));

        ConceptAssignment ca = ConceptAssignment.builder()
                .id(1L).conceptContent(concept).teacher(docente).grade("4").section("B").active(true)
                .assignedAt(LocalDateTime.now()).build();
        when(conceptAssignmentRepository.findByConceptContentOrderByAssignedAtDesc(concept))
                .thenReturn(List.of(ca));

        List<SupervisionConceptResponse> result = service.listConcepts();

        assertThat(result).hasSize(1);
        SupervisionConceptResponse item = result.get(0);
        assertThat(item.title()).isEqualTo("Nomenclatura de ácidos");
        assertThat(item.createdByTeacher()).isEqualTo("Pedro Martínez");
        assertThat(item.assignmentCount()).isEqualTo(1L);
        assertThat(item.sections()).hasSize(1);
        assertThat(item.sections().get(0).grade()).isEqualTo("4");
        assertThat(item.sections().get(0).section()).isEqualTo("B");
    }

    // =========================================================================
    // EVALUACIONES
    // =========================================================================

    @Test
    void listEvaluations_incluyeMetadatosYNoExponeClaveDeRespuestas() {
        TeacherProfile docente = teacher(1L, "Laura", "Quispe");
        Evaluation evaluation = evaluation(1L, "Evaluación de óxidos", docente);
        when(evaluationRepository.findAll()).thenReturn(List.of(evaluation));

        EvaluationAssignment ea = EvaluationAssignment.builder()
                .id(1L).evaluation(evaluation).teacher(docente).grade("5").section("A").active(true)
                .assignedAt(LocalDateTime.now()).build();
        when(evaluationAssignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation))
                .thenReturn(List.of(ea));
        when(evaluationQuestionRepository.countByEvaluationAndActiveTrue(evaluation)).thenReturn(8L);
        when(evaluationAttemptRepository.countByEvaluationAndStatusIn(eq(evaluation), any()))
                .thenReturn(12L);

        List<SupervisionEvaluationResponse> result = service.listEvaluations();

        assertThat(result).hasSize(1);
        SupervisionEvaluationResponse item = result.get(0);
        assertThat(item.title()).isEqualTo("Evaluación de óxidos");
        assertThat(item.createdByTeacher()).isEqualTo("Laura Quispe");
        assertThat(item.questionCount()).isEqualTo(8L);
        assertThat(item.submittedAttempts()).isEqualTo(12L);
        assertThat(item.assignmentCount()).isEqualTo(1L);

        // Garantía de seguridad: la respuesta es solo de metadatos; no existe ningún
        // componente de preguntas/alternativas/clave en este tipo.
        long camposClave = java.util.Arrays.stream(SupervisionEvaluationResponse.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase())
                .filter(n -> n.contains("correct") || n.contains("answer") || n.contains("question") && n.contains("text"))
                .count();
        assertThat(camposClave).isZero();
    }

    // =========================================================================
    // ASIGNACIONES
    // =========================================================================

    @Test
    void listAssignments_unificaContenidosYEvaluaciones() {
        TeacherProfile docente = teacher(1L, "Pedro", "Martínez");
        ConceptContent concept = concept(1L, "Ácidos", docente);
        Evaluation evaluation = evaluation(1L, "Óxidos", docente);

        ConceptAssignment ca = ConceptAssignment.builder()
                .id(1L).conceptContent(concept).teacher(docente).grade("3").section("A").active(true)
                .assignedAt(LocalDateTime.now().minusDays(1)).build();
        EvaluationAssignment ea = EvaluationAssignment.builder()
                .id(1L).evaluation(evaluation).teacher(docente).grade("3").section("B").active(true)
                .assignedAt(LocalDateTime.now()).build();

        when(conceptAssignmentRepository.findAll()).thenReturn(List.of(ca));
        when(evaluationAssignmentRepository.findAll()).thenReturn(List.of(ea));

        List<SupervisionAssignmentResponse> result = service.listAssignments();

        assertThat(result).hasSize(2);
        // Más reciente primero: la asignación de evaluación.
        assertThat(result.get(0).type()).isEqualTo("EVALUACION");
        assertThat(result.get(0).title()).isEqualTo("Óxidos");
        assertThat(result.get(1).type()).isEqualTo("CONTENIDO");
        assertThat(result.get(1).title()).isEqualTo("Ácidos");
        assertThat(result.get(1).createdByTeacher()).isEqualTo("Pedro Martínez");
    }
}
