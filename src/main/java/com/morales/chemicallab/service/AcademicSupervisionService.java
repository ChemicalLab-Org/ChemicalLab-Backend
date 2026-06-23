package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.SupervisionAssignmentResponse;
import com.morales.chemicallab.dto.SupervisionConceptResponse;
import com.morales.chemicallab.dto.SupervisionEvaluationResponse;
import com.morales.chemicallab.dto.SupervisionSectionRef;
import com.morales.chemicallab.dto.SupervisionSummaryResponse;
import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.ConceptAssignment;
import com.morales.chemicallab.entity.ConceptContent;
import com.morales.chemicallab.entity.ConceptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAssignment;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.repository.ConceptAssignmentRepository;
import com.morales.chemicallab.repository.ConceptContentRepository;
import com.morales.chemicallab.repository.EvaluationAssignmentRepository;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.EvaluationQuestionRepository;
import com.morales.chemicallab.repository.EvaluationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servicio de la supervisión académica del administrador. Reúne, en modo de solo
 * lectura, información institucional sobre contenidos, evaluaciones, asignaciones y
 * un resumen general a partir de los datos ya existentes.
 *
 * <p>No crea, modifica ni elimina datos y nunca expone información sensible: en
 * particular, las vistas de evaluaciones de este servicio jamás incluyen la
 * alternativa correcta ni la clave de respuestas.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicSupervisionService {

    // Estados que cuentan como intento realmente enviado por el estudiante.
    private static final List<AttemptStatus> SUBMITTED_STATES =
            List.of(AttemptStatus.SUBMITTED, AttemptStatus.GRADED);

    private final ConceptContentRepository conceptContentRepository;
    private final ConceptAssignmentRepository conceptAssignmentRepository;
    private final EvaluationRepository evaluationRepository;
    private final EvaluationAssignmentRepository evaluationAssignmentRepository;
    private final EvaluationQuestionRepository evaluationQuestionRepository;
    private final EvaluationAttemptRepository evaluationAttemptRepository;

    // =========================================================================
    // RESUMEN ACADÉMICO
    // =========================================================================

    public SupervisionSummaryResponse getSummary() {
        long totalConcepts = conceptContentRepository.count();
        long publishedConcepts = conceptContentRepository.countByStatus(ConceptStatus.PUBLISHED);
        long totalEvaluations = evaluationRepository.count();
        long publishedEvaluations = evaluationRepository.countByStatus(EvaluationStatus.PUBLISHED);
        long submittedAttempts = evaluationAttemptRepository.countByStatusIn(SUBMITTED_STATES);

        List<ConceptContent> concepts = conceptContentRepository.findAll();
        List<Evaluation> evaluations = evaluationRepository.findAll();

        long teachersWithActivity = countTeachersWithActivity(concepts, evaluations);
        long sectionsWithAssignments = countSectionsWithAssignments();

        return new SupervisionSummaryResponse(
                totalConcepts,
                publishedConcepts,
                totalEvaluations,
                publishedEvaluations,
                teachersWithActivity,
                sectionsWithAssignments,
                submittedAttempts);
    }

    // =========================================================================
    // CONTENIDOS CONCEPTUALES
    // =========================================================================

    public List<SupervisionConceptResponse> listConcepts() {
        return conceptContentRepository.findAll().stream()
                .map(this::toConceptResponse)
                .sorted(Comparator.comparing(SupervisionConceptResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private SupervisionConceptResponse toConceptResponse(ConceptContent concept) {
        List<ConceptAssignment> assignments =
                conceptAssignmentRepository.findByConceptContentOrderByAssignedAtDesc(concept);
        List<SupervisionSectionRef> sections = assignments.stream()
                .map(a -> new SupervisionSectionRef(a.getGrade(), a.getSection(), a.getActive(), a.getAssignedAt()))
                .toList();

        return new SupervisionConceptResponse(
                concept.getId(),
                concept.getTitle(),
                concept.getCategory(),
                concept.getStatus(),
                teacherFullName(concept.getCreatedByTeacher()),
                sections.size(),
                sections,
                concept.getCreatedAt(),
                concept.getUpdatedAt());
    }

    // =========================================================================
    // EVALUACIONES
    // =========================================================================

    public List<SupervisionEvaluationResponse> listEvaluations() {
        return evaluationRepository.findAll().stream()
                .map(this::toEvaluationResponse)
                .sorted(Comparator.comparing(SupervisionEvaluationResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private SupervisionEvaluationResponse toEvaluationResponse(Evaluation evaluation) {
        List<EvaluationAssignment> assignments =
                evaluationAssignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation);
        List<SupervisionSectionRef> sections = assignments.stream()
                .map(a -> new SupervisionSectionRef(a.getGrade(), a.getSection(), a.getActive(), a.getAssignedAt()))
                .toList();

        long questionCount = evaluationQuestionRepository.countByEvaluationAndActiveTrue(evaluation);
        long submittedAttempts = evaluationAttemptRepository.countByEvaluationAndStatusIn(evaluation, SUBMITTED_STATES);

        return new SupervisionEvaluationResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getTopic(),
                evaluation.getStatus(),
                teacherFullName(evaluation.getCreatedByTeacher()),
                questionCount,
                sections.size(),
                submittedAttempts,
                sections,
                evaluation.getCreatedAt(),
                evaluation.getUpdatedAt());
    }

    // =========================================================================
    // ASIGNACIONES (vista unificada de contenidos y evaluaciones)
    // =========================================================================

    public List<SupervisionAssignmentResponse> listAssignments() {
        List<SupervisionAssignmentResponse> assignments = new ArrayList<>();

        for (ConceptAssignment a : conceptAssignmentRepository.findAll()) {
            ConceptContent concept = a.getConceptContent();
            assignments.add(new SupervisionAssignmentResponse(
                    "CONTENIDO",
                    concept.getId(),
                    concept.getTitle(),
                    teacherFullName(a.getTeacher()),
                    a.getGrade(),
                    a.getSection(),
                    a.getActive(),
                    a.getAssignedAt()));
        }

        for (EvaluationAssignment a : evaluationAssignmentRepository.findAll()) {
            Evaluation evaluation = a.getEvaluation();
            assignments.add(new SupervisionAssignmentResponse(
                    "EVALUACION",
                    evaluation.getId(),
                    evaluation.getTitle(),
                    teacherFullName(a.getTeacher()),
                    a.getGrade(),
                    a.getSection(),
                    a.getActive(),
                    a.getAssignedAt()));
        }

        assignments.sort(Comparator.comparing(SupervisionAssignmentResponse::assignedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return assignments;
    }

    // =========================================================================
    // APOYO
    // =========================================================================

    private long countTeachersWithActivity(List<ConceptContent> concepts, List<Evaluation> evaluations) {
        Set<Long> teacherIds = new HashSet<>();
        concepts.forEach(c -> addTeacherId(teacherIds, c.getCreatedByTeacher()));
        evaluations.forEach(e -> addTeacherId(teacherIds, e.getCreatedByTeacher()));
        return teacherIds.size();
    }

    private long countSectionsWithAssignments() {
        Set<String> sections = new HashSet<>();
        conceptAssignmentRepository.findAll().stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .forEach(a -> sections.add(sectionKey(a.getGrade(), a.getSection())));
        evaluationAssignmentRepository.findAll().stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .forEach(a -> sections.add(sectionKey(a.getGrade(), a.getSection())));
        return sections.size();
    }

    private void addTeacherId(Set<Long> teacherIds, TeacherProfile teacher) {
        if (teacher != null && teacher.getId() != null) {
            teacherIds.add(teacher.getId());
        }
    }

    private String sectionKey(String grade, String section) {
        return grade + " " + section;
    }

    private String teacherFullName(TeacherProfile teacher) {
        if (teacher == null) {
            return null;
        }
        return teacher.getNames() + " " + teacher.getLastNames();
    }
}
