package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lógica de negocio de los contenidos conceptuales de química.
 *
 * <p>El docente crea, edita, publica, archiva y asigna sus propios contenidos a
 * grados y secciones. El estudiante solo consulta los contenidos publicados que
 * estén asignados a su grado/sección. El administrador puede consultar todos.</p>
 *
 * <p>El docente y el estudiante se resuelven a partir del nombre de usuario
 * autenticado (no de identificadores enviados por el frontend), de modo que un
 * docente no pueda operar sobre contenidos de otro docente.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ConceptContentService {

    private final ConceptContentRepository conceptContentRepository;
    private final ConceptAssignmentRepository conceptAssignmentRepository;
    private final ConceptMaterialRepository conceptMaterialRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final AuditLogService auditLogService;

    private static final String TARGET_CONCEPT = "ConceptContent";
    private static final int CATEGORY_MAX_LENGTH = 100;

    // =========================================================================
    // DOCENTE
    // =========================================================================

    public ConceptContentResponse createConceptContent(String username, CreateConceptContentRequest request) {
        TeacherProfile teacher = requireTeacher(username);

        ConceptContent content = ConceptContent.builder()
                .title(request.title().trim())
                .category(normalizeCategory(request.category()))
                .summary(cleanText(request.summary()))
                .explanation(cleanText(request.explanation()))
                .formationSteps(sanitizeList(request.formationSteps()))
                .keyPoints(sanitizeList(request.keyPoints()))
                .examples(sanitizeList(request.examples()))
                .suggestedActivity(cleanText(request.suggestedActivity()))
                .createdByTeacher(teacher)
                .status(ConceptStatus.DRAFT)
                .active(true)
                .build();

        conceptContentRepository.save(content);

        auditLogService.recordInfo(LogEventType.CONCEPT_CREATED, TARGET_CONCEPT, content.getId(),
                content.getTitle(), "Crear contenido",
                "Se creó el contenido conceptual «" + content.getTitle() + "».",
                "category=" + content.getCategory());

        return toConceptContentResponse(content);
    }

    @Transactional(readOnly = true)
    public List<ConceptContentResponse> listConceptsByTeacher(String username) {
        TeacherProfile teacher = requireTeacher(username);
        return conceptContentRepository.findByCreatedByTeacherOrderByCreatedAtDesc(teacher)
                .stream()
                .map(this::toConceptContentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConceptContentResponse getConceptByIdForTeacher(String username, Long conceptId) {
        TeacherProfile teacher = requireTeacher(username);
        return toConceptContentResponse(requireOwnedConcept(conceptId, teacher));
    }

    public ConceptContentResponse updateConceptContent(String username, Long conceptId, UpdateConceptContentRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        content.setTitle(request.title().trim());
        content.setCategory(normalizeCategory(request.category()));
        content.setSummary(cleanText(request.summary()));
        content.setExplanation(cleanText(request.explanation()));
        content.setFormationSteps(sanitizeList(request.formationSteps()));
        content.setKeyPoints(sanitizeList(request.keyPoints()));
        content.setExamples(sanitizeList(request.examples()));
        content.setSuggestedActivity(cleanText(request.suggestedActivity()));

        conceptContentRepository.save(content);

        // Las asignaciones a grado/sección no se tocan aquí: la edición conserva
        // intactas las relaciones existentes del contenido.
        auditLogService.recordInfo(LogEventType.CONCEPT_UPDATED, TARGET_CONCEPT, content.getId(),
                content.getTitle(), "Editar contenido",
                "Se actualizó el contenido conceptual «" + content.getTitle() + "».",
                "category=" + content.getCategory());

        return toConceptContentResponse(content);
    }

    public ConceptContentResponse publishConceptContent(String username, Long conceptId) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        if (content.getStatus() == ConceptStatus.ARCHIVED) {
            throw new IllegalArgumentException("No se puede publicar un contenido archivado.");
        }

        // Un contenido debe aportar algo útil antes de publicarse: texto explicativo o, al
        // menos, un material de apoyo (archivo o enlace). Así se evita publicar contenidos vacíos.
        if (!hasUsefulContent(content)) {
            throw new IllegalArgumentException(
                    "No se puede publicar un contenido vacío: agrega texto, un archivo o un enlace de apoyo.");
        }

        content.setStatus(ConceptStatus.PUBLISHED);
        conceptContentRepository.save(content);

        auditLogService.recordInfo(LogEventType.CONCEPT_PUBLISHED, TARGET_CONCEPT, content.getId(),
                content.getTitle(), "Publicar contenido",
                "Se publicó el contenido conceptual «" + content.getTitle() + "».", null);

        return toConceptContentResponse(content);
    }

    public ConceptContentResponse archiveConceptContent(String username, Long conceptId) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        content.setStatus(ConceptStatus.ARCHIVED);
        conceptContentRepository.save(content);

        auditLogService.recordInfo(LogEventType.CONCEPT_ARCHIVED, TARGET_CONCEPT, content.getId(),
                content.getTitle(), "Archivar contenido",
                "Se archivó el contenido conceptual «" + content.getTitle() + "».", null);

        return toConceptContentResponse(content);
    }

    /**
     * Categorías sugeridas para que el docente reutilice etiquetas: combina las
     * categorías por defecto del catálogo con las que el propio docente ya ha usado,
     * sin duplicados (comparación sin distinguir mayúsculas) y ordenadas alfabéticamente.
     */
    @Transactional(readOnly = true)
    public List<String> listCategorySuggestions(String username) {
        TeacherProfile teacher = requireTeacher(username);

        // Clave en minúsculas para deduplicar, conservando la primera grafía vista.
        Map<String, String> unique = new LinkedHashMap<>();
        for (String category : ConceptCategory.DEFAULTS) {
            unique.putIfAbsent(category.toLowerCase(), category);
        }
        for (String category : conceptContentRepository.findDistinctCategoriesByTeacher(teacher)) {
            if (category != null && !category.isBlank()) {
                unique.putIfAbsent(category.trim().toLowerCase(), category.trim());
            }
        }

        return unique.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public ConceptAssignmentResponse assignConceptToSection(String username, Long conceptId, AssignConceptContentRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        if (content.getStatus() == ConceptStatus.ARCHIVED) {
            throw new IllegalArgumentException("No se puede asignar un contenido archivado.");
        }

        String grade = request.grade().trim();
        String section = request.section().trim();

        if (conceptAssignmentRepository.existsByConceptContentAndGradeAndSectionAndActiveTrue(content, grade, section)) {
            throw new IllegalArgumentException("Ya existe una asignación activa para esta sección.");
        }

        ConceptAssignment assignment = ConceptAssignment.builder()
                .conceptContent(content)
                .teacher(teacher)
                .grade(grade)
                .section(section)
                .active(true)
                .build();

        conceptAssignmentRepository.save(assignment);

        auditLogService.recordInfo(LogEventType.CONCEPT_ASSIGNED, TARGET_CONCEPT, content.getId(),
                content.getTitle(), "Asignar contenido",
                "Se asignó el contenido «" + content.getTitle() + "» a " + grade + "° " + section + ".",
                "grade=" + grade + ";section=" + section);

        return toAssignmentResponse(assignment);
    }

    public ConceptAssignmentResponse deactivateAssignment(String username, Long conceptId, Long assignmentId) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        ConceptAssignment assignment = conceptAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new EntityNotFoundException("La asignación no existe."));

        if (!assignment.getConceptContent().getId().equals(content.getId())) {
            throw new IllegalArgumentException("La asignación no pertenece a este contenido.");
        }

        assignment.setActive(false);
        conceptAssignmentRepository.save(assignment);
        return toAssignmentResponse(assignment);
    }

    // =========================================================================
    // ESTUDIANTE
    // =========================================================================

    @Transactional(readOnly = true)
    public List<StudentConceptContentResponse> listPublishedConceptsForStudent(String username) {
        StudentProfile student = requireStudent(username);
        return conceptContentRepository
                .findPublishedForSection(student.getGrade(), student.getSection(), ConceptStatus.PUBLISHED)
                .stream()
                .map(this::toStudentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StudentConceptContentResponse getPublishedConceptForStudent(String username, Long conceptId) {
        StudentProfile student = requireStudent(username);
        ConceptContent content = conceptContentRepository
                .findPublishedForSectionById(conceptId, student.getGrade(), student.getSection(), ConceptStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException(
                        "El contenido debe estar publicado para ser visible al estudiante."));
        return toStudentResponse(content);
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ConceptContentResponse> listAllConcepts() {
        return conceptContentRepository.findAll()
                .stream()
                .map(this::toConceptContentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConceptContentResponse getAnyConceptById(Long conceptId) {
        ConceptContent content = conceptContentRepository.findById(conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El contenido conceptual no existe."));
        return toConceptContentResponse(content);
    }

    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    private TeacherProfile requireTeacher(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un docente.");
        }
        return teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
    }

    private StudentProfile requireStudent(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
        if (user.getRole() != Role.ESTUDIANTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un estudiante.");
        }
        return studentProfileRepository.findByStudentCode(user.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
    }

    /**
     * Carga un contenido y valida que pertenezca al docente indicado. Distingue
     * entre contenido inexistente y contenido de otro docente.
     */
    private ConceptContent requireOwnedConcept(Long conceptId, TeacherProfile teacher) {
        ConceptContent content = conceptContentRepository.findById(conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El contenido conceptual no existe."));
        if (!content.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para modificar este contenido.");
        }
        return content;
    }

    /**
     * Normaliza la categoría de texto libre: recorta los extremos y colapsa los espacios
     * internos repetidos. Valida que no quede vacía ni exceda el límite admitido, de modo
     * que la regla se cumpla aunque la petición no pase por la validación de los DTOs.
     */
    private String normalizeCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            throw new IllegalArgumentException("La categoría es obligatoria.");
        }
        String normalized = rawCategory.trim().replaceAll("\\s+", " ");
        if (normalized.length() > CATEGORY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "La categoría no puede superar " + CATEGORY_MAX_LENGTH + " caracteres.");
        }
        return normalized;
    }

    /**
     * Indica si un contenido aporta información útil: tiene texto (explicación, resumen,
     * pasos, puntos clave, ejemplos o actividad) o, al menos, un material de apoyo activo.
     */
    private boolean hasUsefulContent(ConceptContent content) {
        boolean hasText = isNotBlank(content.getExplanation())
                || isNotBlank(content.getSummary())
                || isNotBlank(content.getSuggestedActivity())
                || !content.getFormationSteps().isEmpty()
                || !content.getKeyPoints().isEmpty()
                || !content.getExamples().isEmpty();
        if (hasText) {
            return true;
        }
        return conceptMaterialRepository.existsByConceptContentIdAndActiveTrue(content.getId());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Normaliza un texto opcional: recorta espacios y convierte el vacío en {@code null}.
     */
    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Normaliza una lista de texto: tolera null, descarta entradas vacías y recorta
     * espacios. Devuelve una lista mutable apta para @ElementCollection.
     */
    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // =========================================================================
    // MÉTODOS PRIVADOS DE MAPEO
    // =========================================================================

    private ConceptContentResponse toConceptContentResponse(ConceptContent content) {
        TeacherProfile teacher = content.getCreatedByTeacher();
        String teacherName = teacher.getNames() + " " + teacher.getLastNames();

        List<ConceptAssignmentResponse> assignments =
                conceptAssignmentRepository.findByConceptContentOrderByAssignedAtDesc(content)
                        .stream()
                        .map(this::toAssignmentResponse)
                        .toList();

        return new ConceptContentResponse(
                content.getId(),
                content.getTitle(),
                content.getCategory(),
                content.getSummary(),
                content.getExplanation(),
                List.copyOf(content.getFormationSteps()),
                List.copyOf(content.getKeyPoints()),
                List.copyOf(content.getExamples()),
                content.getSuggestedActivity(),
                content.getStatus(),
                content.getActive(),
                teacher.getId(),
                teacherName,
                assignments,
                listMaterials(content.getId()),
                content.getCreatedAt(),
                content.getUpdatedAt()
        );
    }

    /**
     * Metadata de los materiales de apoyo de un contenido. Usa una proyección que no carga
     * los bytes de los archivos, de modo que nunca viajen en los listados ni en el detalle.
     */
    private List<ConceptMaterialResponse> listMaterials(Long conceptId) {
        return conceptMaterialRepository.findMetadataByConcept(conceptId).stream()
                .map(view -> ConceptMaterialResponse.fromView(conceptId, view))
                .toList();
    }

    private ConceptAssignmentResponse toAssignmentResponse(ConceptAssignment assignment) {
        return new ConceptAssignmentResponse(
                assignment.getId(),
                assignment.getGrade(),
                assignment.getSection(),
                assignment.getActive(),
                assignment.getAssignedAt()
        );
    }

    private StudentConceptContentResponse toStudentResponse(ConceptContent content) {
        return new StudentConceptContentResponse(
                content.getId(),
                content.getTitle(),
                content.getCategory(),
                content.getSummary(),
                content.getExplanation(),
                List.copyOf(content.getFormationSteps()),
                List.copyOf(content.getKeyPoints()),
                List.copyOf(content.getExamples()),
                content.getSuggestedActivity(),
                listMaterials(content.getId())
        );
    }
}
