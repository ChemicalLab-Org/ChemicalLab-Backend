package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.ConceptMaterialResponse;
import com.morales.chemicallab.dto.CreateMaterialLinkRequest;
import com.morales.chemicallab.dto.MaterialDownload;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.ConceptContentRepository;
import com.morales.chemicallab.repository.ConceptMaterialRepository;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de materiales de apoyo. Validan las reglas de tipo y
 * tamaño de archivo, el saneamiento de URLs, la pertenencia del docente y la visibilidad
 * del estudiante, sin tocar la base de datos.
 */
@ExtendWith(MockitoExtension.class)
class ConceptMaterialServiceTest {

    @Mock private ConceptMaterialRepository conceptMaterialRepository;
    @Mock private ConceptContentRepository conceptContentRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TeacherProfileRepository teacherProfileRepository;
    @Mock private StudentProfileRepository studentProfileRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private ConceptMaterialService service;

    private static final String PDF = "application/pdf";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    // =========================================================================
    // Datos de apoyo
    // =========================================================================

    private TeacherProfile teacher(Long id, String username) {
        UserAccount user = UserAccount.builder()
                .id(id).username(username).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder().id(id).user(user).names("Ana").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code, String grade, String section) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).build();
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code).names("Luis").lastNames("Torres")
                .grade(grade).section(section).build();
    }

    private ConceptContent content(Long id, TeacherProfile owner) {
        return ConceptContent.builder()
                .id(id).title("Enlace químico").category("Enlace químico")
                .explanation("Explicación.").createdByTeacher(owner)
                .status(ConceptStatus.PUBLISHED).active(true).build();
    }

    private void stubTeacher(TeacherProfile teacher) {
        when(userAccountRepository.findByUsername(teacher.getUser().getUsername()))
                .thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser()))
                .thenReturn(Optional.of(teacher));
    }

    // =========================================================================
    // Subida de archivos
    // =========================================================================

    @Test
    void subePdfValido() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        ConceptContent c = content(10L, docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(c));
        when(conceptMaterialRepository.findFirstByConceptContentIdAndTypeAndActiveTrue(10L, MaterialType.FILE))
                .thenReturn(Optional.empty());
        when(conceptMaterialRepository.save(any(ConceptMaterial.class))).thenAnswer(inv -> {
            ConceptMaterial saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        var file = new MockMultipartFile("file", "guia.pdf", PDF, new byte[]{1, 2, 3});
        ConceptMaterialResponse response = service.addFileMaterial("docente1", 10L, file, "Guía");

        assertThat(response.type()).isEqualTo(MaterialType.FILE);
        assertThat(response.originalFileName()).isEqualTo("guia.pdf");
        assertThat(response.previewAvailable()).isTrue();
        assertThat(response.downloadUrl()).isEqualTo("/api/concepts/10/materials/7/download");
        verify(auditLogService).recordInfo(eq(LogEventType.CONCEPT_MATERIAL_ADDED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void subePptxValidoComoDescarga() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));
        when(conceptMaterialRepository.findFirstByConceptContentIdAndTypeAndActiveTrue(10L, MaterialType.FILE))
                .thenReturn(Optional.empty());
        when(conceptMaterialRepository.save(any(ConceptMaterial.class))).thenAnswer(inv -> inv.getArgument(0));

        var file = new MockMultipartFile("file", "clase.pptx", PPTX, new byte[]{1, 2, 3, 4});
        ConceptMaterialResponse response = service.addFileMaterial("docente1", 10L, file, null);

        assertThat(response.type()).isEqualTo(MaterialType.FILE);
        // Las diapositivas no se previsualizan: se ofrecen para descarga.
        assertThat(response.previewAvailable()).isFalse();
    }

    @Test
    void reemplazaArchivoExistenteSinDejarHuerfanos() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));
        ConceptMaterial previo = ConceptMaterial.builder()
                .id(99L).type(MaterialType.FILE).originalFileName("viejo.pdf").active(true).build();
        when(conceptMaterialRepository.findFirstByConceptContentIdAndTypeAndActiveTrue(10L, MaterialType.FILE))
                .thenReturn(Optional.of(previo));
        when(conceptMaterialRepository.save(any(ConceptMaterial.class))).thenAnswer(inv -> inv.getArgument(0));

        var file = new MockMultipartFile("file", "nuevo.pdf", PDF, new byte[]{9, 9});
        service.addFileMaterial("docente1", 10L, file, null);

        // El archivo anterior se elimina para no conservar bytes huérfanos.
        verify(conceptMaterialRepository).delete(previo);
        verify(auditLogService).recordInfo(eq(LogEventType.CONCEPT_MATERIAL_REPLACED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rechazaTipoNoPermitido() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));

        var file = new MockMultipartFile("file", "malware.exe",
                "application/x-msdownload", new byte[]{1, 2});
        assertThatThrownBy(() -> service.addFileMaterial("docente1", 10L, file, null))
                .hasMessageContaining("no permitido");
        verify(conceptMaterialRepository, never()).save(any());
    }

    @Test
    void rechazaArchivoVacio() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));

        var file = new MockMultipartFile("file", "vacio.pdf", PDF, new byte[]{});
        assertThatThrownBy(() -> service.addFileMaterial("docente1", 10L, file, null))
                .hasMessageContaining("adjuntar un archivo");
        verify(conceptMaterialRepository, never()).save(any());
    }

    @Test
    void rechazaArchivoMayorA10Mb() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));

        byte[] big = new byte[10 * 1024 * 1024 + 1];
        var file = new MockMultipartFile("file", "grande.pdf", PDF, big);
        assertThatThrownBy(() -> service.addFileMaterial("docente1", 10L, file, null))
                .hasMessageContaining("10 MB");
        verify(conceptMaterialRepository, never()).save(any());
    }

    @Test
    void rechazaExtensionIncoherenteConContentType() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));

        // Content-Type PDF pero extensión .png: incoherencia rechazada.
        var file = new MockMultipartFile("file", "falso.png", PDF, new byte[]{1, 2});
        assertThatThrownBy(() -> service.addFileMaterial("docente1", 10L, file, null))
                .hasMessageContaining("extensión");
        verify(conceptMaterialRepository, never()).save(any());
    }

    @Test
    void docenteNoAgregaMaterialAContenidoDeOtroDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, otro)));

        var file = new MockMultipartFile("file", "guia.pdf", PDF, new byte[]{1});
        assertThatThrownBy(() -> service.addFileMaterial("docente1", 10L, file, null))
                .hasMessageContaining("No tienes permiso");
        verify(conceptMaterialRepository, never()).save(any());
    }

    // =========================================================================
    // Enlaces externos
    // =========================================================================

    @Test
    void aceptaUrlHttpsValida() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));
        when(conceptMaterialRepository.save(any(ConceptMaterial.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateMaterialLinkRequest("Video", "https://example.com/clase");
        ConceptMaterialResponse response = service.addLinkMaterial("docente1", 10L, request);

        assertThat(response.type()).isEqualTo(MaterialType.LINK);
        assertThat(response.url()).isEqualTo("https://example.com/clase");
        assertThat(response.downloadUrl()).isNull();
        verify(auditLogService).recordInfo(eq(LogEventType.CONCEPT_LINK_ADDED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rechazaUrlJavascript() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(conceptContentRepository.findById(10L)).thenReturn(Optional.of(content(10L, docente)));

        var request = new CreateMaterialLinkRequest("Malo", "javascript:alert(1)");
        assertThatThrownBy(() -> service.addLinkMaterial("docente1", 10L, request))
                .hasMessageContaining("http");
        verify(conceptMaterialRepository, never()).save(any());
    }

    // =========================================================================
    // Descarga / visibilidad
    // =========================================================================

    @Test
    void estudianteDescargaMaterialDeContenidoAsignado() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(conceptContentRepository.findPublishedForSectionById(10L, "3", "A", ConceptStatus.PUBLISHED))
                .thenReturn(Optional.of(content(10L, teacher(1L, "docente1"))));

        ConceptMaterial material = ConceptMaterial.builder()
                .id(7L).type(MaterialType.FILE).contentType(PDF)
                .originalFileName("guia.pdf").fileData(new byte[]{1, 2, 3}).active(true).build();
        when(conceptMaterialRepository.findByIdAndConceptContentIdAndActiveTrue(7L, 10L))
                .thenReturn(Optional.of(material));

        MaterialDownload download = service.downloadMaterial("EST0001", 10L, 7L);

        assertThat(download.data()).hasSize(3);
        assertThat(download.contentType()).isEqualTo(PDF);
        assertThat(download.inline()).isTrue();
    }

    @Test
    void estudianteNoDescargaMaterialDeContenidoNoAsignado() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        when(userAccountRepository.findByUsername("EST0002")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0002")).thenReturn(Optional.of(alumno));
        when(conceptContentRepository.findPublishedForSectionById(10L, "3", "B", ConceptStatus.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadMaterial("EST0002", 10L, 7L))
                .hasMessageContaining("no está disponible");
        verify(conceptMaterialRepository, never())
                .findByIdAndConceptContentIdAndActiveTrue(any(), any());
    }
}
