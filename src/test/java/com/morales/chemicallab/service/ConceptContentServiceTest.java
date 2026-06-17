package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de contenidos conceptuales. Se mockean los
 * repositorios para validar la lógica de negocio: creación, publicación,
 * asignación y, sobre todo, las reglas de pertenencia y de visibilidad por rol.
 */
@ExtendWith(MockitoExtension.class)
class ConceptContentServiceTest {

    @Mock
    private ConceptContentRepository conceptContentRepository;
    @Mock
    private ConceptAssignmentRepository conceptAssignmentRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;

    @InjectMocks
    private ConceptContentService service;

    // =========================================================================
    // Datos de apoyo
    // =========================================================================

    private TeacherProfile teacher(Long id, String username) {
        UserAccount user = UserAccount.builder()
                .id(id).username(username).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder()
                .id(id).user(user).names("Ana").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code, String grade, String section) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).build();
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code).names("Luis").lastNames("Torres")
                .grade(grade).section(section).build();
    }

    private ConceptContent content(Long id, TeacherProfile owner, ConceptStatus status) {
        return ConceptContent.builder()
                .id(id)
                .title("Óxidos básicos")
                .category(ConceptCategory.OXIDOS)
                .explanation("Explicación de los óxidos.")
                .createdByTeacher(owner)
                .status(status)
                .active(true)
                .build();
    }

    private void stubTeacher(TeacherProfile teacher) {
        when(userAccountRepository.findByUsername(teacher.getUser().getUsername()))
                .thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser()))
                .thenReturn(Optional.of(teacher));
    }

    private void stubStudent(StudentProfile student) {
        when(userAccountRepository.findByUsername(student.getUser().getUsername()))
                .thenReturn(Optional.of(student.getUser()));
        when(studentProfileRepository.findByStudentCode(student.getStudentCode()))
                .thenReturn(Optional.of(student));
    }

    // =========================================================================
    // 1. Docente crea contenido
    // =========================================================================

    @Test
    void docenteCreaContenidoEnBorrador() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.save(any(ConceptContent.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateConceptContentRequest(
                "  Óxidos básicos  ", ConceptCategory.OXIDOS, "Resumen", "Explicación de los óxidos.",
                List.of("Paso 1", "  ", "Paso 2"), List.of("Clave 1"), List.of());

        ConceptContentResponse response = service.createConceptContent("docente1", request);

        assertThat(response.title()).isEqualTo("Óxidos básicos");
        assertThat(response.status()).isEqualTo(ConceptStatus.DRAFT);
        assertThat(response.createdByTeacherId()).isEqualTo(1L);
        // La lista se normaliza: se descartan entradas en blanco.
        assertThat(response.formationSteps()).containsExactly("Paso 1", "Paso 2");
        verify(conceptContentRepository).save(any(ConceptContent.class));
    }

    // =========================================================================
    // 2. Docente lista sus contenidos
    // =========================================================================

    @Test
    void docenteListaSusContenidos() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findByCreatedByTeacherOrderByCreatedAtDesc(docente))
                .thenReturn(List.of(content(10L, docente, ConceptStatus.DRAFT)));

        List<ConceptContentResponse> result = service.listConceptsByTeacher("docente1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
    }

    // =========================================================================
    // 3. Docente publica contenido
    // =========================================================================

    @Test
    void docentePublicaContenido() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        ConceptContent c = content(10L, docente, ConceptStatus.DRAFT);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(c));
        when(conceptContentRepository.save(any(ConceptContent.class))).thenAnswer(inv -> inv.getArgument(0));

        ConceptContentResponse response = service.publishConceptContent("docente1", 10L);

        assertThat(response.status()).isEqualTo(ConceptStatus.PUBLISHED);
    }

    // =========================================================================
    // 4. Docente asigna contenido a grado/sección
    // =========================================================================

    @Test
    void docenteAsignaContenidoASeccion() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        ConceptContent c = content(10L, docente, ConceptStatus.PUBLISHED);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(c));
        when(conceptAssignmentRepository.existsByConceptContentAndGradeAndSectionAndActiveTrue(c, "3", "A"))
                .thenReturn(false);
        when(conceptAssignmentRepository.save(any(ConceptAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ConceptAssignmentResponse response =
                service.assignConceptToSection("docente1", 10L, new AssignConceptContentRequest("3", "A"));

        assertThat(response.grade()).isEqualTo("3");
        assertThat(response.section()).isEqualTo("A");
        assertThat(response.active()).isTrue();
    }

    // =========================================================================
    // 5. Estudiante de esa sección ve el contenido publicado
    // =========================================================================

    @Test
    void estudianteDeLaSeccionVeContenidoPublicado() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(conceptContentRepository.findPublishedForSection("3", "A", ConceptStatus.PUBLISHED))
                .thenReturn(List.of(content(10L, docente, ConceptStatus.PUBLISHED)));

        List<StudentConceptContentResponse> result = service.listPublishedConceptsForStudent("EST0001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Óxidos básicos");
    }

    // =========================================================================
    // 6. Estudiante de otra sección no puede ver el contenido
    // =========================================================================

    @Test
    void estudianteDeOtraSeccionNoVeContenido() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        when(conceptContentRepository.findPublishedForSectionById(10L, "3", "B", ConceptStatus.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublishedConceptForStudent("EST0002", 10L))
                .hasMessageContaining("publicado");
    }

    // =========================================================================
    // 7. Estudiante no ve contenidos en borrador
    // =========================================================================

    @Test
    void estudianteNoVeContenidosEnBorrador() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        // El repositorio solo devuelve PUBLISHED; un borrador no aparece en la consulta.
        when(conceptContentRepository.findPublishedForSection("3", "A", ConceptStatus.PUBLISHED))
                .thenReturn(List.of());

        List<StudentConceptContentResponse> result = service.listPublishedConceptsForStudent("EST0001");

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // 8. Docente no puede editar contenido de otro docente
    // =========================================================================

    @Test
    void docenteNoEditaContenidoDeOtroDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, otro, ConceptStatus.DRAFT)));

        var request = new UpdateConceptContentRequest(
                "Nuevo", ConceptCategory.GENERAL, null, "Explicación", null, null, null);

        assertThatThrownBy(() -> service.updateConceptContent("docente1", 10L, request))
                .hasMessageContaining("No tienes permiso");
        verify(conceptContentRepository, never()).save(any(ConceptContent.class));
    }

    // =========================================================================
    // 9. No se duplica una asignación activa
    // =========================================================================

    @Test
    void noSeDuplicaAsignacionActiva() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        ConceptContent c = content(10L, docente, ConceptStatus.PUBLISHED);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(c));
        when(conceptAssignmentRepository.existsByConceptContentAndGradeAndSectionAndActiveTrue(c, "3", "A"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.assignConceptToSection("docente1", 10L, new AssignConceptContentRequest("3", "A")))
                .hasMessageContaining("Ya existe una asignación activa");
        verify(conceptAssignmentRepository, never()).save(any(ConceptAssignment.class));
    }

    // =========================================================================
    // 10. No se asigna un contenido archivado
    // =========================================================================

    @Test
    void noSeAsignaContenidoArchivado() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        ConceptContent c = content(10L, docente, ConceptStatus.ARCHIVED);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.assignConceptToSection("docente1", 10L, new AssignConceptContentRequest("3", "A")))
                .hasMessageContaining("archivado");
        verify(conceptAssignmentRepository, never()).save(any(ConceptAssignment.class));
    }
}
